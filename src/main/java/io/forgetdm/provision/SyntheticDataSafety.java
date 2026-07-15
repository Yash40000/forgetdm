package io.forgetdm.provision;

import java.util.Locale;

import static java.sql.Types.BIGINT;
import static java.sql.Types.DECIMAL;
import static java.sql.Types.DOUBLE;
import static java.sql.Types.FLOAT;
import static java.sql.Types.INTEGER;
import static java.sql.Types.NUMERIC;
import static java.sql.Types.REAL;
import static java.sql.Types.SMALLINT;
import static java.sql.Types.TINYINT;

final class SyntheticDataSafety {
    record Classification(String category, String generator, boolean sourceDistributionAllowed, String warning) {
        boolean sensitive() {
            return category != null && !category.isBlank()
                    && !"SAFE_CATEGORICAL".equals(category)
                    && !"TECHNICAL_IDENTIFIER".equals(category);
        }
    }

    private SyntheticDataSafety() {}

    static Classification classify(String column, int jdbcType, String typeName) {
        String n = norm(column);
        String words = " " + n.replaceAll("[^a-z0-9]+", " ") + " ";

        if (containsAny(words, " ssn ", " social security ", " tax id ", " tin ", " national id ", " govt id ", " government id ")) {
            return sensitive("PII_TAX_ID", n.contains("ssn") ? "SSN" : "ALPHANUMERIC");
        }
        if (technicalIdentifier(words)) {
            String generator = numericType(jdbcType) ? "SEQUENCE" : "PADDED_SEQUENCE";
            return new Classification("TECHNICAL_IDENTIFIER", generator, false,
                    "Source identifier values suppressed; a deterministic sequence will be generated");
        }
        if (containsAny(words, " status ", " state ", " type ", " code ", " flag ", " indicator ", " category ",
                " channel ", " currency ", " method ", " frequency ", " reason ", " source system ")) {
            return new Classification("SAFE_CATEGORICAL", suggestedGenerator(column), true, "");
        }
        if (containsAny(words, " email ", " mail ", " emailaddress ", " email address ")) {
            return sensitive("PII_EMAIL", "EMAIL");
        }
        if (containsAny(words, " phone ", " mobile ", " cell ", " telephone ", " tel ")) {
            return sensitive("PII_PHONE", "PHONE_US");
        }
        if (containsAny(words, " iban ")) return sensitive("FINANCIAL_ACCOUNT", "IBAN_LIKE");
        if (containsAny(words, " bic ", " swift ")) return sensitive("FINANCIAL_ACCOUNT", "BIC");
        if (containsAny(words, " routing ", " aba ")) return sensitive("FINANCIAL_ACCOUNT", "ROUTING_NUMBER_US");
        if (containsAny(words, " credit score ", " credit rating ", " risk score ")) {
            return sensitive("BANKING_CONTROL", null);
        }
        if (containsAny(words, " card ", " pan ", " payment card ", " debit card ", " credit card ")) {
            return sensitive("PCI_CARD", "CREDIT_CARD_VISA");
        }
        if (containsAny(words, " account ", " acct ")) return sensitive("FINANCIAL_ACCOUNT", "ACCOUNT_NUMBER");
        if (containsAny(words, " dob ", " birth ", " birthdate ", " date of birth ")) return sensitive("PII_DOB", "DOB_ADULT");
        if (femaleHint(words) && containsAny(words, " first ", " name ")) return sensitive("PII_NAME", "FEMALE_FIRST_NAME");
        if (maleHint(words) && containsAny(words, " first ", " name ")) return sensitive("PII_NAME", "MALE_FIRST_NAME");
        if (containsAny(words, " first name ", " fname ", " given name ")) return sensitive("PII_NAME", "FIRST_NAME");
        if (containsAny(words, " last name ", " lname ", " surname ", " family name ")) return sensitive("PII_NAME", "LAST_NAME");
        if (containsAny(words, " name ", " customer name ", " full name ", " cardholder ")) return sensitive("PII_NAME", "FULL_NAME");
        if (containsAny(words, " address ", " addr ", " street ")) return sensitive("PII_ADDRESS", "STREET_ADDRESS");
        if (containsAny(words, " city ")) return sensitive("PII_ADDRESS", "CITY_BY_COUNTRY");
        if (containsAny(words, " zip ", " postal ", " postcode ", " pincode ", " pin code ")) return sensitive("PII_ADDRESS", "POSTAL_BY_COUNTRY");
        if (containsAny(words, " country ")) return new Classification("SAFE_CATEGORICAL", "COUNTRY_CODE", true, "");

        if (containsAny(words, " merchant ", " payee ", " beneficiary ", " counterparty ", " description ",
                " descriptor ", " memo ", " narrative ", " narration ", " remittance ")) {
            return sensitive("TRANSACTION_DESCRIPTOR", containsAny(words, " merchant ", " payee ", " beneficiary ", " counterparty ") ? "COMPANY" : "LOREM_SENTENCE");
        }
        if (containsAny(words, " balance ", " amount ", " limit ", " income ", " salary ", " payment ", " fee ",
                " interest ", " rate ", " exposure ", " delinquency ", " chargeback ")) {
            return sensitive("FINANCIAL_VALUE", null);
        }
        if (containsAny(words, " kyc ", " aml ", " sanction ", " risk ", " fraud ")) {
            return sensitive("BANKING_CONTROL", suggestedGenerator(column));
        }

        return new Classification("", suggestedGenerator(column), true, "");
    }

    static String suggestedGenerator(String col) {
        String n = norm(col);
        String words = " " + n.replaceAll("[^a-z0-9]+", " ") + " ";
        if (containsAny(words, " ssn ")) return "SSN";
        if (containsAny(words, " tax id ", " tin ", " national id ", " govt id ", " government id ")) return "ALPHANUMERIC";
        if (technicalIdentifier(words)) return "PADDED_SEQUENCE";
        if (containsAny(words, " status ")) return "STATUS";
        if (containsAny(words, " email ", " mail ")) return "EMAIL";
        if (containsAny(words, " phone ", " mobile ", " tel ")) return "PHONE_US";
        if (femaleHint(words) && containsAny(words, " first ", " name ")) return "FEMALE_FIRST_NAME";
        if (maleHint(words) && containsAny(words, " first ", " name ")) return "MALE_FIRST_NAME";
        if (containsAny(words, " first ")) return "FIRST_NAME";
        if (containsAny(words, " last ", " surname ", " family ")) return "LAST_NAME";
        if (containsAny(words, " dob ", " birth ")) return "DOB_ADULT";
        if (containsAny(words, " name ")) return "FULL_NAME";
        if (containsAny(words, " city ")) return "CITY_BY_COUNTRY";
        if (containsAny(words, " state ", " province ")) return "STATE_BY_COUNTRY";
        if (containsAny(words, " zip ", " postal ", " pincode ", " pin ")) return "POSTAL_BY_COUNTRY";
        if (containsAny(words, " street ", " address ", " addr ")) return "ADDRESS_BY_COUNTRY";
        if (containsAny(words, " country ")) return "COUNTRY_CODE";
        if (containsAny(words, " iban ")) return "IBAN_LIKE";
        if (containsAny(words, " bic ", " swift ")) return "BIC";
        if (containsAny(words, " account ", " acct ")) return "ACCOUNT_NUMBER";
        if (containsAny(words, " routing ", " aba ")) return "ROUTING_NUMBER_US";
        if (containsAny(words, " card ", " pan ")) return "CREDIT_CARD_VISA";
        if (containsAny(words, " company ", " org ", " employer ", " merchant ")) return "COMPANY";
        if (containsAny(words, " uuid ", " guid ")) return "UUID";
        if (containsAny(words, " ip ")) return "IPV4";
        if (containsAny(words, " url ", " link ")) return "URL";
        return "ALPHANUMERIC";
    }

    static boolean directSafeGenerator(String generator) {
        if (generator == null || generator.isBlank()) return false;
        return !"ALPHANUMERIC".equals(generator) && !"STATUS".equals(generator);
    }

    private static Classification sensitive(String category, String generator) {
        String warning = "Banking safe profile suppressed source top-values for " + category;
        return new Classification(category, generator == null ? "" : generator, false, warning);
    }

    private static boolean femaleHint(String words) {
        return containsAny(words, " female ", " mother ", " wife ", " daughter ", " girl ");
    }

    private static boolean maleHint(String words) {
        return containsAny(words, " male ", " father ", " husband ", " son ", " boy ");
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static boolean technicalIdentifier(String words) {
        return containsAny(words, " id ", " key ", " pk ", " row id ", " surrogate key ");
    }

    private static boolean numericType(int jdbcType) {
        return switch (jdbcType) {
            case TINYINT, SMALLINT, INTEGER, BIGINT, NUMERIC, DECIMAL, FLOAT, REAL, DOUBLE -> true;
            default -> false;
        };
    }

    private static String norm(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').trim();
    }

}
