# phenotype-annotation-pipeline

Loads phenotype annotations into the RGD `FULL_ANNOT` table from two upstream sources:

- **MGI** — Mouse Phenotype (MP) annotations from `MGI_PhenoGenoMP.rpt`.
- **HPO** — Human Phenotype Ontology annotations from `genes_to_phenotype.txt`, enriched with PubMed IDs from `phenotype.hpoa`.

Each source is handled by its own importer; both share the same caching / diffing / stale-delete logic in `BaseImporter`.

## Running

The pipeline is invoked through `run.sh` with one of two flags:

```sh
./run.sh -MGIPhenotype     # load mouse (MP) annotations
./run.sh -HPOPhenotype     # load human (HPO) annotations
```

Cron wrappers `mousePhenotypeImport.sh` and `humanPhenotypeImport.sh` call `run.sh` with the appropriate flag and email the per-run summary log.

## What each run does

1. Pre-load all annotations for the configured `refRgdId` into an in-memory cache (`AnnotCache`).
2. Download the source file (with a date-stamped local copy under `data/`); HPO additionally downloads `phenotype.hpoa` to harvest PubMed IDs keyed by `(hpo_id | OMIM/ORPHA id)`.
3. For each incoming row, build an `Annotation` and ask the cache whether it is new, unchanged, or modified:
   - **New** → `INSERT` (logged to `inserted_annots.log`).
   - **Modified** (DATA_SRC or NOTES differ) → `UPDATE` (logged to `updated_annots.log`).
   - **Unchanged** → `LAST_MODIFIED_DATE` is bumped so the row isn't classified as stale.
4. After processing, any cache entry whose `LAST_MODIFIED_DATE` predates the run start is treated as stale and `DELETE`d (logged to `deleted_annots.log`) — provided the count does not exceed the `staleAnnotThreshold` (a percentage; default `5`). Mass deletions abort with a warning instead of going through.

## Configuration

Per-importer settings live in `properties/AppConfigure.xml`:

| Property | MGI | HPO |
|---|---|---|
| `fileURL` | MGI `MGI_PhenoGenoMP.rpt` URL | HPO `genes_to_phenotype.txt` URL |
| `phenotypeFile` | — | HPO `phenotype.hpoa` URL (PubMed lookup) |
| `workDirectory` | `data` | `data` |
| `owner` | `206` (MGI loader user) | `66` (HPO loader user) |
| `dataSource` | `MGI` | `HPO` |
| `refRgdId` | `5509061` | `8699517` |
| `evidenceCode` | (computed: `IAGP` or `IEA`) | `IAGP` |
| `staleAnnotThreshold` | `5` (percent) | `5` (percent) |
| `minFileSize` | — | `500000` (bytes, compressed) |

## Source layout

```
src/main/java/edu/mcw/rgd/dataload/
  ImportManager.java         CLI dispatcher; selects MGI or HPO bean
  BaseImporter.java          shared run loop, stale-delete with threshold
  AnnotCache.java            in-memory cache; insert / update / stale diff
  AnnotationImportDao.java   DAO wrapper + small object/RGD-ID caches
  MGIPhenotypeImporter.java  MGI_PhenoGenoMP.rpt parser
  HPOPhenotypeImporter.java  genes_to_phenotype.txt + phenotype.hpoa parser
```

## Logs

Per-importer logs are written to `logs/`:

- `status_human.log`, `summary_human.log` — high-level events for HPO runs.
- `status_mouse.log`, `summary_mouse.log` — same for MGI runs.
- `inserted_annots.log`, `updated_annots.log`, `deleted_annots.log` — per-row detail.
- `core.log` — debug-level catch-all.

The cron wrapper emails the `summary_<species>.log` after each run.

## Build

```sh
./gradlew installDist
```
Produces a runnable distribution under `build/install/phenotype-annotation-pipeline/`.
