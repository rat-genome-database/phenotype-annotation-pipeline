package edu.mcw.rgd.dataload;

import edu.mcw.rgd.datamodel.ObjectWithName;
import edu.mcw.rgd.datamodel.ObjectWithSymbol;
import edu.mcw.rgd.datamodel.RgdId;
import edu.mcw.rgd.datamodel.XdbId;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.process.FileDownloader;
import edu.mcw.rgd.process.Utils;

import java.io.*;
import java.util.*;


/**
 * @author mtutaj
 * @since June 3, 2014
 * Imports annotations from HPO
 */
public class HPOPhenotypeImporter extends BaseImporter {

    private String phenotypeFile;
    private long minFileSize;

    private Map<String,String> unprocessed = new HashMap<>();
    private int newRec=0;

    private String evidenceCode;

    final String EXPECTED_HEADER="ncbi_gene_id\tgene_symbol\thpo_id\thpo_name\tfrequency\tdisease_id";

    /**
     * download the file, parse it, create pheno annotations
     * <p>
     * new file format: (as of Jun 2023) genes_to_phenotype.txt file -- they added columns 'frequency' and 'disease_id'
     * <pre>
     ncbi_gene_id    gene_symbol     hpo_id  hpo_name        frequency       disease_id
     10      NAT2    HP:0000007      Autosomal recessive inheritance -       OMIM:243400
     16      AARS1   HP:0002460      Distal muscle weakness  15/15   OMIM:613287
     * </pre><p>
     * file format: (as of Apr 2023) genes_to_phenotype.txt file
     * <pre>
     * ncbi_gene_id [tab] gene_symbol [tab] hpo_id [tab] hpo_name
     * 10      NAT2    HP:0000007      Autosomal recessive inheritance
     * </pre>
     * @throws Exception
     */
    public void run() throws Exception{

        super.run();

        List<Annotation> incomingAnnotations = loadIncomingAnnotations();
        Collection<Annotation> mergedAnnotations = mergeAnnotations(incomingAnnotations);
        log.info("  merged "+incomingAnnotations.size()+" into "+mergedAnnotations.size()+" annotations by xref");

        for( Annotation a: mergedAnnotations ) {

            if( insertOrUpdateAnnotation(a)!=0 ) {
                newRec++;
            }
        }

        if( newRec!=0 ) {
            log.info("  "+Utils.formatThousands(newRec) + " annotations have been inserted");
        }

        int modifiedAnnotCount = updateAnnotations();
        if( modifiedAnnotCount!=0 ) {
            log.info("  "+Utils.formatThousands(modifiedAnnotCount)+" annotations have been updated");
        }

        deleteStaleAnnotations();

        int upRec = getCountOfUpToDateAnnots();
        log.info("  "+Utils.formatThousands(upRec) + " annotations are up-to-date");

        log.info("  "+Utils.formatThousands(unprocessed.keySet().size()) + " records skipped; skipped genes: "+unprocessed.entrySet());
    }

    List<Annotation> loadIncomingAnnotations() throws Exception{

        Map<String, String> hpWithOmim2PmidMap = loadPubMedIds();

        FileDownloader fd = new FileDownloader();
        fd.setExternalFile(this.getFileURL());
        fd.setLocalFile(this.getWorkDirectory() + "/" + "HPOPheno.txt");
        fd.setAppendDateStamp(true);
        fd.setUseCompression(true);

        String importedFile = fd.downloadNew();

        File f = new File(importedFile);

        log.info("processing file of size " + Utils.formatThousands(f.length()) + "\n");

        if (f.length()  < getMinFileSize()) {
            throw new Exception("FILE LENGTH TOO SHORT - PLEASE REVIEW - PIPELINE DID NOT RUN!");
        }

        BufferedReader br = Utils.openReader(importedFile);

        // check the header line
        String line = br.readLine();
        if( !line.equalsIgnoreCase(EXPECTED_HEADER) ) {
            throw new Exception("***** UNEXPECTED HEADER! *****");
        }

        List<Annotation> incomingAnnotations = new ArrayList<>();

        //ncbi_gene_id    gene_symbol     hpo_id  hpo_name        frequency       disease_id
        //16      AARS1   HP:0002460      Distal muscle weakness  15/15   OMIM:613287
        while ((line = br.readLine()) != null) {
            String[] tokens = line.split("\\t", -1);

            if( tokens.length<6 ) {
                log.warn("malformed line: "+line);
                continue;
            }
            String geneSymbol = tokens[1];
            String geneId = tokens[0];
            String hpoId = tokens[2];
            String hpoTermName = tokens[3];
            String frequency = tokens[4];
            String omimOrphaId = tokens[5];

            // disease_id: convert OMIM:xxxxxx to MIM:xxxxxx
            String diseaseId = omimOrphaId.replace("OMIM:", "MIM:");

            String hpOmimId = hpoId+"|"+omimOrphaId;
            String pmid = hpWithOmim2PmidMap.get(hpOmimId);

            String xrefSource = diseaseId;
            if( pmid!=null ) {
                xrefSource += "|"+pmid;
            }

            List<RgdId> rgdIds = getGenesByGeneId(geneId);

            if (rgdIds.size() < 1) {
                this.unprocessed.put(geneId, geneSymbol);
            } else {
                RgdId id = rgdIds.get(0);

                if( xrefSource.contains("OMIM") || xrefSource.contains("http") ) {
                    System.out.println("problem");
                }
                createAnnotation(id, getEvidenceCode(), hpoId, hpoTermName, xrefSource, incomingAnnotations);
            }
        }
        br.close();

        return incomingAnnotations;
    }

    List<RgdId> getGenesByGeneId(String geneId) throws Exception {
        List<RgdId> rgdIds = dao.getRGDIdsByXdbId(XdbId.XDB_KEY_NCBI_GENE, geneId);
        rgdIds.removeIf(id -> id.getObjectKey() != RgdId.OBJECT_KEY_GENES);
        return rgdIds;
    }

    Map<String, String> loadPubMedIds() throws Exception {

        Map<String, String> hpWithOmim2PmidMap = new HashMap<>();

        FileDownloader fd = new FileDownloader();
        fd.setExternalFile(getPhenotypeFile());
        fd.setLocalFile(this.getWorkDirectory() + "/" + "phenotype.hpoa");
        fd.setAppendDateStamp(true);
        fd.setUseCompression(true);

        String importedFile = fd.downloadNew();

        BufferedReader in = Utils.openReader(importedFile);
        String line;
        while( (line=in.readLine())!=null ) {

            // sample lines:
            //database_id	disease_name	qualifier	hpo_id	reference	evidence	onset	frequency	sex	modifier	aspect	biocuration
            //OMIM:619340	Developmental and epileptic encephalopathy 96		HP:0011097	PMID:31675180	PCS		1/2			P	HPO:probinson[2021-06-21]
            // ORPHA:1777	Temtamy syndrome		HP:0002970	ORPHA:1777	TAS		HP:0040282			P	ORPHA:orphadata[2025-03-03]

            // process only lines starting with 'OMIM:' or 'ORPHA:'
            if( !(line.startsWith("OMIM:") || line.startsWith("ORPHA:")) ) {
                continue;
            }
            String[] cols = line.split("[\\t]", -1);
            String omimOrpha = cols[0];
            String hpoId = cols[3];
            String pmidId = cols[4];

            // there could be multiple PMIDs separated by ';'
            Set<String> pmidIds = new TreeSet<>();
            String[] ids = pmidId.split("[\\;]");

            for( String id: ids ) {

                if (id.startsWith("PMID:")) {
                    pmidIds.add(id);
                }
            }

            if( !pmidIds.isEmpty() ) {
                String pmidIdsStr = Utils.concatenate(pmidIds, "|");
                String key = hpoId + "|" + omimOrpha;
                hpWithOmim2PmidMap.put(key, pmidIdsStr);
            }
        }
        in.close();

        return hpWithOmim2PmidMap;
    }

    /**
     * Inserts an annotation into the datastore
     * @param id rgd id
     * @param evidence evidence code
     * @param accId accession id
     * @param term term name
     * @param xrefSource MIM:xxxxxx or ORPHA:xxx concatenated with PMID id
     * @throws Exception
     */
    void createAnnotation(RgdId id, String evidence, String accId, String term, String xrefSource, List<Annotation> incomingAnnotations) throws Exception {

        Annotation annot = new Annotation();

        annot.setAnnotatedObjectRgdId(id.getRgdId());
        annot.setAspect("H");
        annot.setCreatedBy(getOwner());
        annot.setCreatedDate(new Date());
        annot.setDataSrc(getDataSource());
        annot.setEvidence(evidence);
        annot.setLastModifiedDate(new Date());
        annot.setLastModifiedBy(getOwner());
        annot.setRgdObjectKey(id.getObjectKey());
        annot.setXrefSource(xrefSource);

        Object rgdObj = dao.getObject(id.getRgdId());
        if( rgdObj instanceof ObjectWithName ) {
            annot.setObjectName(((ObjectWithName)rgdObj).getName());
        }
        if( rgdObj instanceof ObjectWithSymbol ) {
            annot.setObjectSymbol(((ObjectWithSymbol)rgdObj).getSymbol());
        }

        annot.setRefRgdId(getRefRgdId());

        annot.setTermAcc(accId);
        annot.setTerm(term);

        incomingAnnotations.add(annot);
    }

    // merge by XREF_SOURCE: all other fields the same
    Collection<Annotation> mergeAnnotations( List<Annotation> incomingAnnotations ) throws CloneNotSupportedException {

        Map<String, Annotation> mergedAnnotations = new HashMap<>();

        for( Annotation ann: incomingAnnotations ) {
            Annotation a = (Annotation) ann.clone();
            String mergeKey = createMergeKey(a);
            Annotation ma = mergedAnnotations.get(mergeKey);
            if( ma==null ) {
                mergedAnnotations.put(mergeKey, a);
            } else {
                if( ma.getXrefSource()==null ) {
                    ma.setXrefSource(a.getXrefSource());
                } else {
                    Set<String> xrefs = new TreeSet<>();
                    xrefs.add(ma.getXrefSource());
                    xrefs.add(a.getXrefSource());
                    String xrefsStr = Utils.concatenate(xrefs, "|");
                    ma.setXrefSource(xrefsStr);
                }
            }
        }

        return mergedAnnotations.values();
    }

    private String createMergeKey(Annotation a) {
        return a.getAnnotatedObjectRgdId()+"|"+a.getTermAcc()+"|"+a.getRefRgdId()
                +"|" + Utils.defaultString(a.getQualifier())
                +"|" + Utils.defaultString(a.getWithInfo())
                +"|" + Utils.defaultString(a.getEvidence());
    }

    public long getMinFileSize() {
        return minFileSize;
    }

    public void setMinFileSize(long minFileSize) {
        this.minFileSize = minFileSize;
    }

    public void setEvidenceCode(String evidenceCode) {
        this.evidenceCode = evidenceCode;
    }

    public String getEvidenceCode() {
        return evidenceCode;
    }

    public String getLoggerName() {
        return "status_human";
    }

    public String getPhenotypeFile() {
        return phenotypeFile;
    }

    public void setPhenotypeFile(String phenotypeFile) {
        this.phenotypeFile = phenotypeFile;
    }
}
