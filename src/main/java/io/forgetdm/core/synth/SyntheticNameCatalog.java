package io.forgetdm.core.synth;

import io.forgetdm.core.util.SeedLists;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Expanded, locale/gender-aware name catalog (~4k first / ~10k last per locale). Built deterministically
 * (insertion-ordered set over fixed root/bridge/ending lists), so a stable index into the returned list is
 * reproducible run-to-run — which is what lets the deterministic masking engine reuse it as a large substitution
 * dictionary, not just the synthetic generator.
 */
public final class SyntheticNameCatalog {
    private static final int TARGET_FIRST_NAMES = 4_000;
    private static final int TARGET_LAST_NAMES = 10_000;

    private static final Map<String, List<String>> CACHE = new ConcurrentHashMap<>();
    /** How many real names sit at the FRONT of each cached list (before synthesized overflow). */
    private static final Map<String, Integer> REAL_COUNT = new ConcurrentHashMap<>();
    /** % of name picks drawn from the real-name prefix; the rest may be synthesized (for distinctness at scale). */
    static final int REAL_NAME_BIAS = 20;

    private SyntheticNameCatalog() {}

    public static List<String> firstNames(String locale, String gender) {
        String loc = normalizeLocale(locale);
        String gen = normalizeGender(gender);
        return CACHE.computeIfAbsent("first|" + loc + "|" + gen, k -> buildFirstNames(loc, gen, k));
    }

    public static List<String> lastNames(String locale) {
        String loc = normalizeLocale(locale);
        return CACHE.computeIfAbsent("last|" + loc, k -> buildLastNames(loc, k));
    }

    static int realFirstCount(String locale, String gender) {
        firstNames(locale, gender);   // ensure built (records REAL_COUNT)
        return REAL_COUNT.getOrDefault("first|" + normalizeLocale(locale) + "|" + normalizeGender(gender), 0);
    }

    static int realLastCount(String locale) {
        lastNames(locale);
        return REAL_COUNT.getOrDefault("last|" + normalizeLocale(locale), 0);
    }

    /**
     * Pick a name, blending REAL names (the front {@code realCount} entries) with the synthesized tail so typical
     * output stays familiar while high-volume runs avoid tiny-dictionary repeat pressure. The remaining picks use the
     * synthesized tail to provide distinctness. For unique columns the caller's retry-then-suffix logic still
     * sources distinct values from the tail, so realism here does not weaken uniqueness.
     */
    static String pickPreferReal(java.util.Random r, List<String> names, int realCount) {
        if (names == null || names.isEmpty()) return "";
        int bound = (realCount > 0 && realCount < names.size() && r.nextInt(100) < REAL_NAME_BIAS)
                ? realCount : names.size();
        return names.get(r.nextInt(bound));
    }

    static long fullNameSpace(String locale, String gender) {
        return (long) firstNames(locale, gender).size() * lastNames(locale).size();
    }

    private static List<String> buildFirstNames(String locale, String gender, String cacheKey) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if ("GLOBAL".equals(locale)) names.addAll(SeedLists.get("first_names.txt"));
        if ("ANY".equals(gender) || "M".equals(gender)) addBase(names, maleBase(locale));
        if ("ANY".equals(gender) || "F".equals(gender)) addBase(names, femaleBase(locale));
        REAL_COUNT.put(cacheKey, names.size());   // everything so far is a real name; the rest is synthesized

        List<String> roots = firstRoots(locale, gender);
        List<String> bridges = firstBridges(locale, gender);
        List<String> endings = firstEndings(locale, gender);
        for (String root : roots) {
            for (String bridge : bridges) {
                for (String ending : endings) {
                    addName(names, root + bridge + ending);
                    if (names.size() >= TARGET_FIRST_NAMES) return List.copyOf(names);
                }
            }
        }
        return List.copyOf(names);
    }

    private static List<String> buildLastNames(String locale, String cacheKey) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if ("GLOBAL".equals(locale)) names.addAll(SeedLists.get("last_names.txt"));
        addBase(names, lastBase(locale));
        REAL_COUNT.put(cacheKey, names.size());   // real surnames first; synthesized overflow follows

        // Synthesize with AT MOST ONE affix to avoid compound monstrosities (e.g. "Brookfisherland"):
        // bare root, then prefix+root, then root+suffix.
        List<String> prefixes = lastPrefixes(locale);
        List<String> roots = lastRoots(locale);
        List<String> suffixes = lastSuffixes(locale);
        for (String root : roots) {
            addName(names, root);
            if (names.size() >= TARGET_LAST_NAMES) return List.copyOf(names);
        }
        for (String prefix : prefixes) {
            if (prefix.isEmpty()) continue;
            for (String root : roots) {
                addName(names, prefix + root.toLowerCase(Locale.ROOT));
                if (names.size() >= TARGET_LAST_NAMES) return List.copyOf(names);
            }
        }
        for (String root : roots) {
            for (String suffix : suffixes) {
                if (suffix.isEmpty()) continue;
                addName(names, root + suffix);
                if (names.size() >= TARGET_LAST_NAMES) return List.copyOf(names);
            }
        }
        for (String prefix : prefixes) {
            if (prefix.isEmpty()) continue;
            for (String root : roots) {
                for (String suffix : suffixes) {
                    if (suffix.isEmpty()) continue;
                    addName(names, prefix + root.toLowerCase(Locale.ROOT) + suffix.toLowerCase(Locale.ROOT));
                    if (names.size() >= TARGET_LAST_NAMES) return List.copyOf(names);
                }
            }
        }
        return List.copyOf(names);
    }

    private static String normalizeLocale(String value) {
        if (value == null || value.isBlank()) return "GLOBAL";
        String v = value.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "IN", "IND", "INDIA", "HI", "HINDI" -> "IN";
            case "UK", "GB", "GBR", "UNITED_KINGDOM", "UNITED KINGDOM", "EN_GB" -> "UK";
            case "US", "USA", "UNITED_STATES", "UNITED STATES", "EN_US" -> "US";
            default -> "GLOBAL";
        };
    }

    private static String normalizeGender(String value) {
        if (value == null || value.isBlank()) return "ANY";
        String v = value.trim().toUpperCase(Locale.ROOT);
        if (v.startsWith("M")) return "M";
        if (v.startsWith("F") || v.startsWith("W")) return "F";
        return "ANY";
    }

    private static void addBase(LinkedHashSet<String> out, List<String> values) {
        values.forEach(v -> addName(out, v));
    }

    private static void addName(LinkedHashSet<String> out, String raw) {
        if (raw == null) return;
        String clean = raw.trim().replaceAll("[^A-Za-z -]", "");
        if (clean.length() < 2) return;
        out.add(title(clean));
    }

    private static String title(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean nextUpper = true;
        for (char c : value.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == ' ' || c == '-') {
                out.append(c);
                nextUpper = true;
            } else if (nextUpper) {
                out.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                out.append(c);
            }
        }
        return fixSurnamePrefixes(out.toString());
    }

    private static String fixSurnamePrefixes(String value) {
        String s = value;
        if (s.startsWith("Mc") && s.length() > 2) return "Mc" + Character.toUpperCase(s.charAt(2)) + s.substring(3);
        if (s.startsWith("Mac") && s.length() > 3) return "Mac" + Character.toUpperCase(s.charAt(3)) + s.substring(4);
        if (s.startsWith("Van") && s.length() > 3) return "Van" + Character.toUpperCase(s.charAt(3)) + s.substring(4);
        if (s.startsWith("De") && s.length() > 2) return "De" + Character.toUpperCase(s.charAt(2)) + s.substring(3);
        if (s.startsWith("St") && s.length() > 2) return "St" + Character.toUpperCase(s.charAt(2)) + s.substring(3);
        return s;
    }

    private static List<String> maleBase(String locale) {
        return switch (locale) {
            case "IN" -> IN_MALE;
            case "UK" -> concat(UK_MALE, US_MALE);
            case "US" -> US_MALE;
            default -> concat(US_MALE, UK_MALE, IN_MALE);
        };
    }

    private static List<String> femaleBase(String locale) {
        return switch (locale) {
            case "IN" -> IN_FEMALE;
            case "UK" -> concat(UK_FEMALE, US_FEMALE);
            case "US" -> US_FEMALE;
            default -> concat(US_FEMALE, UK_FEMALE, IN_FEMALE);
        };
    }

    private static List<String> lastBase(String locale) {
        return switch (locale) {
            case "IN" -> IN_LAST;
            case "UK" -> concat(UK_LAST, US_LAST);
            case "US" -> US_LAST;
            default -> concat(US_LAST, UK_LAST, IN_LAST);
        };
    }

    private static List<String> firstRoots(String locale, String gender) {
        if ("IN".equals(locale)) {
            if ("M".equals(gender)) return IN_MALE_ROOTS;
            if ("F".equals(gender)) return IN_FEMALE_ROOTS;
            return concat(IN_MALE_ROOTS, IN_FEMALE_ROOTS);
        }
        if ("F".equals(gender)) return FEMALE_ROOTS;
        if ("M".equals(gender)) return MALE_ROOTS;
        return concat(MALE_ROOTS, FEMALE_ROOTS);
    }

    private static List<String> firstBridges(String locale, String gender) {
        if ("IN".equals(locale)) return IN_BRIDGES;
        return "F".equals(gender) ? FEMALE_BRIDGES : MALE_BRIDGES;
    }

    private static List<String> firstEndings(String locale, String gender) {
        if ("IN".equals(locale)) {
            if ("M".equals(gender)) return IN_MALE_ENDINGS;
            if ("F".equals(gender)) return IN_FEMALE_ENDINGS;
            return concat(IN_MALE_ENDINGS, IN_FEMALE_ENDINGS);
        }
        if ("F".equals(gender)) return FEMALE_ENDINGS;
        if ("M".equals(gender)) return MALE_ENDINGS;
        return concat(MALE_ENDINGS, FEMALE_ENDINGS);
    }

    private static List<String> lastPrefixes(String locale) {
        return "IN".equals(locale) ? IN_LAST_PREFIXES : GLOBAL_LAST_PREFIXES;
    }

    private static List<String> lastRoots(String locale) {
        return "IN".equals(locale) ? IN_LAST_ROOTS : GLOBAL_LAST_ROOTS;
    }

    private static List<String> lastSuffixes(String locale) {
        return "IN".equals(locale) ? IN_LAST_SUFFIXES : GLOBAL_LAST_SUFFIXES;
    }

    @SafeVarargs
    private static <T> List<T> concat(List<T>... lists) {
        List<T> out = new ArrayList<>();
        for (List<T> list : lists) out.addAll(list);
        return out;
    }

    private static final List<String> US_MALE = List.of(
            "James", "John", "Robert", "Michael", "William", "David", "Richard", "Joseph", "Thomas", "Charles",
            "Christopher", "Daniel", "Matthew", "Anthony", "Mark", "Donald", "Steven", "Paul", "Andrew", "Joshua",
            "Kenneth", "Kevin", "Brian", "George", "Edward", "Ronald", "Timothy", "Jason", "Jeffrey", "Ryan",
            "Jacob", "Gary", "Nicholas", "Eric", "Jonathan", "Stephen", "Larry", "Justin", "Scott", "Brandon",
            "Benjamin", "Samuel", "Gregory", "Alexander", "Patrick", "Frank", "Raymond", "Jack", "Dennis", "Jerry",
            "Tyler", "Aaron", "Jose", "Adam", "Nathan", "Henry", "Zachary", "Douglas", "Peter", "Kyle");

    private static final List<String> US_FEMALE = List.of(
            "Mary", "Patricia", "Jennifer", "Linda", "Elizabeth", "Barbara", "Susan", "Jessica", "Sarah", "Karen",
            "Lisa", "Nancy", "Betty", "Margaret", "Sandra", "Ashley", "Kimberly", "Emily", "Donna", "Michelle",
            "Carol", "Amanda", "Melissa", "Deborah", "Stephanie", "Rebecca", "Laura", "Sharon", "Cynthia", "Kathleen",
            "Amy", "Angela", "Shirley", "Anna", "Brenda", "Pamela", "Nicole", "Emma", "Samantha", "Katherine",
            "Christine", "Debra", "Rachel", "Catherine", "Carolyn", "Janet", "Ruth", "Maria", "Heather", "Diane",
            "Julie", "Joyce", "Victoria", "Kelly", "Lauren", "Christina", "Joan", "Evelyn", "Olivia", "Megan");

    private static final List<String> UK_MALE = List.of(
            "Oliver", "George", "Arthur", "Noah", "Muhammad", "Leo", "Oscar", "Harry", "Archie", "Jack",
            "Henry", "Charlie", "Freddie", "Theodore", "Thomas", "Finley", "Alfie", "Jacob", "William", "Lucas",
            "Tommy", "Isaac", "Alexander", "Joshua", "Edward", "James", "Joseph", "Logan", "Daniel", "Samuel",
            "Ethan", "Max", "Adam", "Benjamin", "Harrison", "Mason", "Sebastian", "Dylan", "Rory", "Louis");

    private static final List<String> UK_FEMALE = List.of(
            "Olivia", "Amelia", "Isla", "Ava", "Ivy", "Freya", "Lily", "Florence", "Mia", "Willow",
            "Rosie", "Sophia", "Isabella", "Grace", "Daisy", "Sienna", "Poppy", "Elsie", "Emily", "Ella",
            "Evelyn", "Phoebe", "Sofia", "Evie", "Charlotte", "Harper", "Millie", "Matilda", "Maya", "Sophie",
            "Alice", "Jessica", "Erin", "Imogen", "Molly", "Ruby", "Chloe", "Eliza", "Eva", "Luna");

    private static final List<String> IN_MALE = List.of(
            "Aarav", "Vivaan", "Aditya", "Arjun", "Reyansh", "Muhammad", "Sai", "Arnav", "Ayaan", "Krishna",
            "Ishaan", "Shaurya", "Atharv", "Advait", "Vihaan", "Kabir", "Rohan", "Rahul", "Raj", "Amit",
            "Vikram", "Sanjay", "Karan", "Nikhil", "Dev", "Ravi", "Anil", "Manish", "Suresh", "Rakesh",
            "Vijay", "Deepak", "Pranav", "Yash", "Harsh", "Rishi", "Kunal", "Akash", "Varun", "Siddharth",
            "Sameer", "Neeraj", "Ajay", "Abhinav", "Tanish", "Rudra", "Aryan", "Mohan", "Naveen", "Gaurav");

    private static final List<String> IN_FEMALE = List.of(
            "Aadhya", "Ananya", "Diya", "Saanvi", "Myra", "Aarohi", "Anika", "Ira", "Sara", "Pari",
            "Kiara", "Riya", "Ishita", "Kavya", "Priya", "Neha", "Pooja", "Sneha", "Anjali", "Divya",
            "Nisha", "Meera", "Asha", "Sunita", "Lakshmi", "Radhika", "Shreya", "Swati", "Preeti", "Kiran",
            "Kritika", "Isha", "Nandini", "Tanvi", "Vidya", "Maya", "Tara", "Ritu", "Seema", "Payal",
            "Aditi", "Avni", "Navya", "Siya", "Trisha", "Mahi", "Jiya", "Vani", "Charvi", "Rhea");

    private static final List<String> US_LAST = List.of(
            "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis", "Rodriguez", "Martinez",
            "Hernandez", "Lopez", "Gonzalez", "Wilson", "Anderson", "Thomas", "Taylor", "Moore", "Jackson", "Martin",
            "Lee", "Perez", "Thompson", "White", "Harris", "Sanchez", "Clark", "Ramirez", "Lewis", "Robinson",
            "Walker", "Young", "Allen", "King", "Wright", "Scott", "Torres", "Nguyen", "Hill", "Flores",
            "Green", "Adams", "Nelson", "Baker", "Hall", "Rivera", "Campbell", "Mitchell", "Carter", "Roberts");

    private static final List<String> UK_LAST = List.of(
            "Smith", "Jones", "Taylor", "Brown", "Williams", "Wilson", "Johnson", "Davies", "Patel", "Wright",
            "Walker", "White", "Edwards", "Hughes", "Green", "Hall", "Lewis", "Harris", "Clarke", "Mason",
            "Mitchell", "Cooper", "Hill", "Ward", "Turner", "Carter", "Phillips", "Collins", "Baker", "Allen",
            "Morris", "Rogers", "Cook", "Morgan", "Bell", "Murphy", "Bailey", "Parker", "Miller", "Davis");

    private static final List<String> IN_LAST = List.of(
            "Patel", "Singh", "Kumar", "Sharma", "Gupta", "Verma", "Mehta", "Reddy", "Rao", "Iyer",
            "Nair", "Menon", "Pillai", "Das", "Dutta", "Ghosh", "Banerjee", "Mukherjee", "Chatterjee", "Bose",
            "Sen", "Roy", "Chopra", "Kapoor", "Khanna", "Malhotra", "Bajaj", "Bedi", "Gill", "Kaur",
            "Joshi", "Shah", "Agarwal", "Desai", "Kulkarni", "Bhatt", "Chaudhary", "Mishra", "Tiwari", "Yadav");

    private static final List<String> MALE_ROOTS = List.of(
            "Al", "An", "Ar", "Ben", "Cal", "Cam", "Dan", "Dev", "El", "Evan", "Finn", "Gar", "Ian", "Ja",
            "Jon", "Ken", "Leo", "Liam", "Mal", "Mat", "Max", "Nolan", "Owen", "Ray", "Reed", "Sam", "Theo",
            "Tom", "Wes", "Zach", "Bran", "Clay", "Drew", "Em", "Glen", "Hale", "Jace", "Kale", "Luca", "Miles");

    private static final List<String> FEMALE_ROOTS = List.of(
            "Ada", "Ala", "Am", "Ana", "Ari", "Ava", "Bella", "Bri", "Cara", "Celia", "Dani", "Ella", "Em",
            "Eva", "Gia", "Hana", "Ivy", "Jessa", "Kara", "Kira", "Lana", "Lia", "Lila", "Mara", "Maya",
            "Nora", "Ol", "Ria", "Sara", "Talia", "Vera", "Zara", "Alina", "Cora", "Dara", "Elia", "Mila");

    private static final List<String> MALE_BRIDGES = List.of("", "b", "d", "l", "m", "n", "r", "s", "t", "v");
    private static final List<String> FEMALE_BRIDGES = List.of("", "l", "m", "n", "r", "s", "v");
    private static final List<String> MALE_ENDINGS = List.of(
            "an", "en", "on", "el", "er", "in", "as", "ian", "iel", "son", "ton", "ley", "ford", "well", "den", "ard");
    private static final List<String> FEMALE_ENDINGS = List.of(
            "a", "ia", "ina", "ara", "elle", "lyn", "isha", "ita", "ani", "ali", "ora", "ela", "ena", "ine", "ette", "ellea");

    private static final List<String> IN_MALE_ROOTS = List.of(
            "Aar", "Abh", "Adi", "Aj", "Ak", "An", "Ar", "Dev", "Har", "Ish", "Jay", "Kav", "Kri", "Man",
            "Nikh", "Pran", "Raj", "Roh", "Sam", "Sha", "Tan", "Vi", "Viv", "Yash", "Neer", "Rav", "Amit", "Siddh");
    private static final List<String> IN_FEMALE_ROOTS = List.of(
            "Aar", "Adi", "An", "Av", "Char", "Di", "Ish", "Jiya", "Kav", "Kri", "Lak", "Mee", "Nee", "Pri",
            "Radh", "Riya", "Sai", "Shre", "Tan", "Va", "Vid", "Ya", "Nav", "Saan", "My", "Par", "Ki");
    private static final List<String> IN_BRIDGES = List.of("", "a", "i", "n", "r", "v", "y", "m", "s", "h", "l");
    private static final List<String> IN_MALE_ENDINGS = List.of(
            "av", "an", "it", "esh", "endra", "deep", "pal", "raj", "veer", "ank", "ant", "ish", "ul", "ay", "am", "ar");
    private static final List<String> IN_FEMALE_ENDINGS = List.of(
            "a", "ika", "isha", "ita", "ani", "ali", "ya", "vi", "na", "ree", "ini", "ra", "shri", "preet", "maya");

    private static final List<String> GLOBAL_LAST_PREFIXES = List.of(
            "", "North", "South", "East", "West", "Ash", "Brook", "Clear", "Fair", "Green", "Hart", "Lake",
            "Oak", "River", "Rock", "Silver", "Stone", "Wood", "Mc", "Mac", "Van", "De", "St");
    private static final List<String> GLOBAL_LAST_ROOTS = List.of(
            "Adams", "Alden", "Archer", "Bailey", "Baker", "Bell", "Bennett", "Blake", "Brock", "Brooks",
            "Carter", "Clark", "Cole", "Collins", "Cooper", "Cross", "Dale", "Dalton", "Day", "Dean",
            "Ellis", "Fisher", "Ford", "Foster", "Gray", "Green", "Hall", "Hardy", "Harris", "Hart",
            "Hayes", "Hill", "Hunt", "James", "King", "Knight", "Lane", "Lawson", "Lee", "Lewis",
            "Mason", "Miller", "Moore", "Morgan", "Nelson", "Parker", "Price", "Reed", "Rivers", "Scott",
            "Shaw", "Stone", "Taylor", "Turner", "Walker", "Ward", "Webb", "White", "Wood", "Young");
    private static final List<String> GLOBAL_LAST_SUFFIXES = List.of(
            "", "s", "er", "man", "son", "ton", "ley", "well", "field", "ford", "brook", "wood", "worth", "stone", "land", "ridge");

    private static final List<String> IN_LAST_PREFIXES = List.of(
            "", "Raj", "Dev", "Hari", "Sri", "Nava", "Vijay", "Anand", "Bharat", "Surya", "Kiran", "Mohan");
    private static final List<String> IN_LAST_ROOTS = List.of(
            "Pat", "Singh", "Kum", "Sharm", "Gupt", "Verm", "Mehr", "Redd", "Rao", "Iyer",
            "Nair", "Men", "Pill", "Das", "Dutt", "Ghosh", "Baner", "Mukher", "Chatt", "Bose",
            "Sen", "Roy", "Chopr", "Kapoor", "Khann", "Malhotr", "Bajaj", "Bedi", "Gill", "Kaur",
            "Josh", "Shah", "Agar", "Des", "Kulkarn", "Bhatt", "Chaudh", "Mishr", "Tiwar", "Yadav",
            "Shett", "Naik", "Pawar", "Jain", "Saxen", "Bhatn", "Aror", "Seth", "Lal", "Pandey");
    private static final List<String> IN_LAST_SUFFIXES = List.of(
            "", "a", "an", "ani", "wal", "wala", "kar", "ekar", "jee", "ji", "ra", "pal", "dev", "nath", "lal", "ar", "esh", "pur");
}
