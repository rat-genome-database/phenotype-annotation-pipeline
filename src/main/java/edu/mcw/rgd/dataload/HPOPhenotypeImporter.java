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

    /**
     * download the file, parse it, create pheno annotations
     * <p>
     * new file format: (as of Apr 2023) genes_to_phenotype.txt file
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
     * <p>
     * new file format: (as of March 2020) phenotype_to_genes.txt file
     * <pre>
     * #Format: HPO-id [tab] HPO label [tab] entrez-gene-id [tab] entrez-gene-symbol [tab] Additional Info from G-D source [tab] G-D source [tab] disease-ID for link
     * HP:0000002	Abnormality of body height	3954	LETM1	-	mim2gene	OMIM:194190
     * </pre>
     * old file format:
     * <pre>
     * #Format: diseaseId [tab] gene-symbol [tab] gene-id [tab] HPO-ID [tab] HPO-term-name
     * OMIM:302800	GJB1	2705	HP:0002015	Dysphagia
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

        log.info("Processing File of size " + f.length() + "\n");

        if (f.length()  < getMinFileSize()) {
            throw new Exception("FILE LENGTH TOO SHORT - PLEASE REVIEW - PIPELINE DID NOT RUN!");
        }

        BufferedReader br = Utils.openReader(importedFile);

        // skip the header line
        String line = br.readLine();

        // ncbi_gene_id    gene_symbol     hpo_id  hpo_name
        // 10      NAT2    HP:0000007      Autosomal recessive inheritance
        while ((line = br.readLine()) != null) {
            String[] tokens = line.split("\\t", -1);

            if( tokens.length<4 ) {
                log.warn("malformed line: "+line);
                continue;
            }
            String geneSymbol = tokens[1];
            String geneId = tokens[0];
            String hpoId = tokens[2];
            String hpoTermName = tokens[3];

            List<RgdId> rgdIds = getGenesByGeneId(geneId);

            if (rgdIds.size() < 1) {
                this.unprocessed.put(geneId, geneSymbol);
            } else {
                RgdId id = rgdIds.get(0);

                if( insertOrUpdateAnnotation(id, getEvidenceCode(), hpoId, hpoTermName)!=0 ) {
                    log.debug("inserted " + id + " " + hpoId);
                    newRec++;
                }
            }
        }
        br.close();

        log.info("Phenotype Pipeline report for " + new Date());
        if( newRec!=0 ) {
            log.info(newRec + " annotations have been inserted");
        }

        int modifiedAnnotCount = updateAnnotations();
        if( modifiedAnnotCount!=0 ) {
            log.info(modifiedAnnotCount+" annotations have been updated");
        }

        deleteStaleAnnotations();

        int upRec = getCountOfUpToDateAnnots();
        log.info(upRec + " annotations are up-to-date");

        log.info(unprocessed.keySet().size() + " records have been skipped");

        log.info("Skipped genes: "+unprocessed.entrySet().toString());
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
     * @return count of rows affected
     * @throws Exception
     */
    int insertOrUpdateAnnotation(RgdId id, String evidence, String accId, String term) throws Exception {

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
