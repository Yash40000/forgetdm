export type ForgeTheme =
  | 'default'
  | 'dark'
  | 'light'
  | 'midnight'
  | 'azure'
  | 'sage'
  | 'slate'
  | 'hc'
  | 'soccer'
  | 'soccer-pro';

export type ForgeThemeDefinition = {
  value: ForgeTheme;
  label: string;
  description: string;
  mode: 'light' | 'dark';
};

export const FORGE_THEME_KEY = 'forgetdm.theme';

export const forgeThemes: ForgeThemeDefinition[] = [
  {
    value: 'default',
    label: 'Forge Default',
    description: 'Clean Next UI baseline',
    mode: 'light'
  },
  {
    value: 'dark',
    label: 'Dark+',
    description: 'Previous UI VS Code dark',
    mode: 'dark'
  },
  {
    value: 'light',
    label: 'Light+',
    description: 'Previous UI VS Code light',
    mode: 'light'
  },
  {
    value: 'midnight',
    label: 'Midnight Blue',
    description: 'Deep blue operator console',
    mode: 'dark'
  },
  {
    value: 'azure',
    label: 'Blue White',
    description: 'Bright data workspace',
    mode: 'light'
  },
  {
    value: 'sage',
    label: 'Soft Sage',
    description: 'Calm green compliance view',
    mode: 'light'
  },
  {
    value: 'slate',
    label: 'Soft Slate',
    description: 'Neutral enterprise grey',
    mode: 'light'
  },
  {
    value: 'hc',
    label: 'High Contrast',
    description: 'Maximum contrast accessibility',
    mode: 'dark'
  },
  {
    value: 'soccer',
    label: 'Soccer Pitch',
    description: 'Match-day green, still business-safe',
    mode: 'dark'
  },
  {
    value: 'soccer-pro',
    label: 'Soccer Pro',
    description: 'Club-inspired accents by workspace',
    mode: 'dark'
  }
];

export function isForgeTheme(value: string | null | undefined): value is ForgeTheme {
  return forgeThemes.some((theme) => theme.value === value);
}

export function themeFor(value: ForgeTheme) {
  return forgeThemes.find((theme) => theme.value === value) ?? forgeThemes[0];
}
