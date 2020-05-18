package edu.mcw.rgd.dataload;

import edu.mcw.rgd.datamodel.ObjectWithName;
import edu.mcw.rgd.datamodel.ObjectWithSymbol;
import edu.mcw.rgd.datamodel.RgdId;
import edu.mcw.rgd.datamodel.XdbId;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.datamodel.ontologyx.Term;
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
    private String unmappedPhenotypesFile;
    private String unmappedDiseasesFile;

    private Map<String,String> unprocessed = new HashMap<>();
    private int newRec=0;

    private String evidenceCode;

    /**
     * download the file, parse it, create pheno annotations
     * <p>
     * new file format: (as of May 2020) genes_to_phenotype.txt file
     * <pre>
     * Format: entrez-gene-id [tab] entrez-gene-symbol [tab] HPO-Term-Name [tab] HPO-Term-ID [tab] Frequency-Raw [tab] Frequency-HPO [tab] Additional Info from G-D source [tab] G-D source<tab>disease-ID for link
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

        DiseaseAnnotationQC diseaseQC = new DiseaseAnnotationQC();
        diseaseQC.setDao(dao);

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
        BufferedWriter bw = new BufferedWriter(new FileWriter(getUnmappedPhenotypesFile()));

        // skip the header line
        String line = br.readLine();
        bw.write(line+"\n");

        Set<String> unmappedDiseaseIds = new HashSet<>();

        //             0                   1                         2              3               4               5                    6                             7                    8
        // Format: entrez-gene-id<tab>entrez-gene-symbol<tab>HPO-Term-Name<tab>HPO-Term-ID<tab>Frequency-Raw<tab>Frequency-HPO<tab>Additional Info from G-D source<tab>G-D source<tab>disease-ID for link
        //8192	CLPP	HP:0004322	Short stature		HP:0040283	-	mim2gene	OMIM:614129
        //2	A2M	HP:0001300	Parkinsonism			susceptibility	mim2gene	OMIM:104300
        while ((line = br.readLine()) != null) {
            String[] tokens = line.split("\\t", -1);

            String diseaseId = tokens[8]; // OMIM or Orphanet id
            String geneSymbol = tokens[1];
            String geneId = tokens[0];
            String hpoId = tokens[3];
            String hpoTermName = tokens[2];

            List<RgdId> rgdIds = getGenesByGeneId(geneId);

            if (rgdIds.size() < 1) {
                this.unprocessed.put(geneId, geneSymbol);
            } else {
                RgdId id = rgdIds.get(0);

                String notes = diseaseId;
                Term rdoTerm = diseaseQC.qc(id.getRgdId(), diseaseId);
                if( rdoTerm==null ) {
                    bw.write(line+"\n");
                    unmappedDiseaseIds.add(diseaseId);
                }

                if( insertOrUpdateAnnotation(id, getEvidenceCode(), hpoId, hpoTermName, notes, rdoTerm)!=0 ) {
                    log.debug("inserted " + id + " " + hpoId + " " + notes + " "+(rdoTerm==null?"":rdoTerm.getAccId()));
                    newRec++;
                }
            }
        }
        br.close();
        bw.close();

        log.info("Phenotype Pipeline report for " + new Date().toString());
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

        handleUnmappedDiseaseIds(unmappedDiseaseIds);

        log.info(unprocessed.keySet().size() + " records have been skipped");

        diseaseQC.dumpSummary(log);

        log.info("Skipped genes: "+unprocessed.entrySet().toString());
    }

    void handleUnmappedDiseaseIds(Set<String> unmappedDiseaseIds) throws Exception {

        BufferedWriter bw = new BufferedWriter(new FileWriter(getUnmappedDiseasesFile()));
        bw.write("Disease Id\n");

        for( String omimId: unmappedDiseaseIds ) {
            bw.write(omimId+"\n");
        }
        bw.close();
    }

    List<RgdId> getGenesByGeneId(String geneId) throws Exception {
        List<RgdId> rgdIds = dao.getRGDIdsByXdbId(XdbId.XDB_KEY_NCBI_GENE, geneId);
        Iterator<RgdId> it = rgdIds.iterator();
        while( it.hasNext() ) {
            RgdId id = it.next();
            if( id.getObjectKey()!=RgdId.OBJECT_KEY_GENES ) {
                it.remove();
            }
        }
        return rgdIds;
    }

    /**
     * Inserts an annotation into the datastore
     * @param id rgd id
     * @param evidence evidence code
     * @param accId accession id
     * @param term term name
     * @param notes optional notes
     * @param relatedTerm Term object representing a term related to the annotated term
     * @return count of rows affected
     * @throws Exception
     */
    int insertOrUpdateAnnotation(RgdId id, String evidence, String accId, String term, String notes, Term relatedTerm) throws Exception {

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
        if( relatedTerm!=null )
            annot.setRelativeTo(relatedTerm.getAccId());
        annot.setNotes(notes);

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

    public void setUnmappedPhenotypesFile(String unmappedPhenotypesFile) {
        this.unmappedPhenotypesFile = unmappedPhenotypesFile;
    }

    public String getUnmappedPhenotypesFile() {
        return unmappedPhenotypesFile;
    }

    public void setUnmappedDiseasesFile(String unmappedDiseasesFile) {
        this.unmappedDiseasesFile = unmappedDiseasesFile;
    }

    public String getUnmappedDiseasesFile() {
        return unmappedDiseasesFile;
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
