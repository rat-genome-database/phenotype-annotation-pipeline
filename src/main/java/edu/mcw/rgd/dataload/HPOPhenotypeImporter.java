package edu.mcw.rgd.dataload;

import edu.mcw.rgd.datamodel.ObjectWithName;
import edu.mcw.rgd.datamodel.ObjectWithSymbol;
import edu.mcw.rgd.datamodel.RgdId;
import edu.mcw.rgd.datamodel.XdbId;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.process.FileDownloader;
import edu.mcw.rgd.process.Utils;
import org.apache.log4j.Logger;

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
    private int upRec=0;
    private int upAnnotNotes=0;

    NotesManager notesManager = new NotesManager();
    private String evidenceCode;

    /**
     * download the file, parse it, create pheno annotations
     * <p>
     * new file format: (as of March 2020)
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

        while ((line = br.readLine()) != null) {
            String[] tokens = line.split("\\t", -1);
            String diseaseId = tokens[6]; // OMIM or Orphanet id
            String geneSymbol = tokens[3];
            String geneId = tokens[2];
            String hpoId = tokens[0];
            String hpoTermName = tokens[1];

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
                } else {
                    upRec++;
                }
            }
        }
        br.close();
        bw.close();

        dao.finishUpdateOfLastModified();

        handleUnmappedDiseaseIds(unmappedDiseaseIds);

        log.info("Phenotype Pipeline report for " + new Date().toString());
        log.info(newRec + " records have been added");
        log.info(upRec + " records have been updated");

        notesManager.updateDb();
        log.info(upAnnotNotes + " annotation notes have been updated");

        deleteStaleAnnotations();

        log.info(unprocessed.keySet().size() + " records have been skipped");

        diseaseQC.dumpSummary(log);

        log.info("Skipped genes: "+unprocessed.entrySet().toString());
    }

    void handleUnmappedDiseaseIds(Set<String> unmappedDiseaseIds) throws Exception {

        // original code:
        // UmlsData.handleUnmappedDiseaseIds(unmappedDiseaseIds, getUnmappedDiseasesFile());
        // new code:

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

        notesManager.add(annot.getKey(), notes);

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

    class NotesManager {
        Map<Integer, Set<String>> notesMap = new HashMap<>();
        Logger logAnnotNotes = Logger.getLogger("updated_annot_notes");

        public void add(int annotKey, String notes) {
            Set<String> setOfNotes = notesMap.get(annotKey);
            if( setOfNotes==null ) {
                setOfNotes = new TreeSet<>();
                notesMap.put(annotKey, setOfNotes);
            }
            setOfNotes.add(notes);
        }

        public void updateDb() throws Exception {

            // update annotation notes if needed
            for( Map.Entry<Integer, Set<String>> entry: notesMap.entrySet() ) {
                String notes = Utils.concatenate(entry.getValue(), " ");
                String notesInRgd = dao.getAnnotationNotes(entry.getKey());
                if( !Utils.stringsAreEqual(notes, notesInRgd) ) {
                    upAnnotNotes++;
                    logAnnotNotes.info("FAK="+entry.getKey()+" OLD=["+Utils.defaultString(notesInRgd)+"] NEW=["+Utils.defaultString(notes)+"]");
                    dao.updateAnnotationNotes(entry.getKey(), notes);
                }
            }
        }
    }
}