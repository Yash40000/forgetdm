/**
 * The Forge Data Design Catalogue.
 *
 * A composable catalogue: testers browse reusable design assets and assemble them into
 * runnable data products, instead of hand-building test data every sprint. The catalogue is
 * organised as a tier ladder — each tier is built from the ones below it:
 *
 *   Generators  → the atomic producers for a single field (synthetic + masking functions)
 *   Value Sets  → curated enumerations & lookups a generator can draw from
 *   Entities    → shaped records / Customer-360 fabrics composed of many fields
 *   Policies    → masking rule sets applied to an entity or source
 *   Recipes     → runnable designs (a DataScope blueprint or synthetic case)
 *   Programs    → published, end-to-end products the whole org can self-serve
 *
 * Every tier is backed by assets ForgeTDM already manages, so the catalogue is a design layer
 * over real building blocks rather than a parallel data store.
 */

export type CatalogTier =
  | 'GENERATOR'
  | 'VALUE_SET'
  | 'ENTITY'
  | 'POLICY'
  | 'RECIPE'
  | 'PROGRAM';

export type CatalogItem = {
  /** Stable within a tier; the tier + id is globally unique. */
  id: string;
  tier: CatalogTier;
  name: string;
  description: string;
  /** Free-text facets used by search and the filter chips. */
  tags: string[];
  /** Small key/value facts shown on the card and in the drawer (e.g. "43 functions"). */
  meta: Record<string, string>;
  /** Route a user can jump to in order to edit the underlying asset, when one exists. */
  manageHref?: string;
};

export type TierDefinition = {
  tier: CatalogTier;
  label: string;      // "Generators"
  singular: string;   // "Generator"
  blurb: string;      // one line describing what the tier produces
  /** Which tier this one composes from — drives the "composes from" hint. */
  composesFrom?: CatalogTier;
};

export type MethodologyStage = {
  key: string;
  label: string;      // MODEL / DESIGN / PROVISION / GOVERN
  headline: string;
  detail: string;
  /** ForgeTDM surfaces that live in this stage. */
  surfaces: string[];
};

/** An item the user has pulled into the Design Tray to compose a product. */
export type DesignSelection = CatalogItem;
