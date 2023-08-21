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
     * </pre><p>
     * new file format: (as of May 2020) genes_to_phenotype.txt file
     * <pre>
     * Format: entrez-gene-id [tab] entrez-gene-symbol [tab] HPO-Term-ID [tab] HPO-Term-Name [tab] Frequency-Raw [tab] Frequency-HPO [tab] Additional Info from G-D source [tab] G-D source<tab>disease-ID for link
     * 8192	CLPP	HP:0004322	Short stature		HP:0040283	-	mim2gene	OMIM:614129
     * 2	A2M	HP:0001300	Parkinsonism			susceptibility	mim2gene	OMIM:104300
     * </pre>
     * @throws Exception
     */
    public void run() throws Exception{

        super.run();

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
            String diseaseId = tokens[5];

            List<RgdId> rgdIds = getGenesByGeneId(geneId);

            if (rgdIds.size() < 1) {
                this.unprocessed.put(geneId, geneSymbol);
            } else {
                RgdId id = rgdIds.get(0);

                if( insertOrUpdateAnnotation(id, getEvidenceCode(), hpoId, hpoTermName, diseaseId)!=0 ) {
                    log.debug("inserted " + id + " " + hpoId);
                    newRec++;
                }
            }
        }
        br.close();

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

    List<RgdId> getGenesByGeneId(String geneId) throws Exception {
        List<RgdId> rgdIds = dao.getRGDIdsByXdbId(XdbId.XDB_KEY_NCBI_GENE, geneId);
        rgdIds.removeIf(id -> id.getObjectKey() != RgdId.OBJECT_KEY_GENES);
        return rgdIds;
    }

    /**
     * Inserts an annotation into the datastore
     * @param id rgd id
     * @param evidence evidence code
     * @param accId accession id
     * @param term term name
     * @param diseaseId OMIM:xxxxxx or ORPHA:xxx
     * @return count of rows affected
     * @throws Exception
     */
    int insertOrUpdateAnnotation(RgdId id, String evidence, String accId, String term, String diseaseId) throws Exception {

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
        annot.setXrefSource(diseaseId);

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

        int result = insertOrUpdateAnnotation(annot);

        return result;
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
}
