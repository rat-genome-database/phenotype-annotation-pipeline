package edu.mcw.rgd.dataload;

import edu.mcw.rgd.datamodel.*;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.process.FileDownloader;
import edu.mcw.rgd.process.Utils;

import java.io.*;
import java.util.*;
import java.util.Map;


/**
 * @author jdepons
 * @since June 28, 2011
 * Imports annotations from MGI
 */
public class MGIPhenotypeImporter extends BaseImporter {

    private Map unprocessed = new HashMap();
    private int newRec=0;
    private int invalidMP=0;
    private final String aspect = "N"; // MP ontology has aspect N

    /**
     * Main method called by the import manager
     * @throws Exception
     */
    public void run() throws Exception{

        super.run();

        FileDownloader fd = new FileDownloader();
        fd.setExternalFile(this.getFileURL());
        fd.setLocalFile(this.getWorkDirectory() + "/" + "MGI_PhenoGenoMP.rpt");
        fd.setAppendDateStamp(true);
        fd.setUseCompression(true);

        String importedFile = fd.downloadNew();
        BufferedReader br = Utils.openReader(importedFile);

        String line;

        while ((line = br.readLine()) != null) {
            String[] tokens = line.split("\\t", -1);
            String accId = tokens[3];
            String[] pmIds = tokens[4].split("[,|]");
            String[] mgiIds = tokens[5].split("[,|]");

            for (String mgiId : mgiIds) {

                Term term = dao.getTermByAccId(accId);
                if( term==null ) {
                    // serious problem : term not found in RGD database
                    String msg = "WARNING: Term " + accId + " not found in RGD database!\n"+
                            "  Please reload MP ontology: (OntologyLoad/run_single.sh MP)";
                    log.warn(msg);
                    invalidMP++;
                    continue;
                }

                List<RgdId> rgdIds = dao.getRGDIdsByXdbId(XdbId.XDB_KEY_MGD, mgiId);

                if (rgdIds.size() < 1) {
                    this.unprocessed.put(mgiId, 0);
                } else {
                    RgdId id = rgdIds.get(0);

                    if (id.getObjectKey() != RgdId.OBJECT_KEY_QTLS && id.getObjectKey() != RgdId.OBJECT_KEY_GENES) {
                        continue;
                    }

                    if (pmIds.length == 1 && pmIds[0].trim().equals("")) {

                        // empty pubmed id
                        List<Annotation> annots = dao.getAnnotationByEvidence(id.getRgdId(), accId, getOwner(), "IAGP");
                        log.debug("found " + annots.size() + " IAGP annotations for null annotation " + id + " " + accId);

                        if (annots.size() == 0) {

                            if( insertOrUpdateAnnotation(id, "IEA", accId, null) != 0 ) {
                                log.debug("inserted " + id + " " + accId);
                                newRec++;
                            }
                        }
                        continue;
                    }

                    for (String pmId : pmIds) {

                        if( insertOrUpdateAnnotation(id, "IAGP", accId, pmId)!=0 ) {
                            log.debug("inserted " + id + " " + accId + " " + pmId);
                            newRec++;
                        }
                    }
                }
            }

        }
        br.close();

        if( newRec!=0 ) {
            log.info(newRec + " annotations have been inserted");
        }

        int modifiedAnnotCount = updateAnnotations();
        if( modifiedAnnotCount!=0 ) {
            log.info(modifiedAnnotCount+" annotations have been updated");
        }

        deleteStaleAnnotations();

        log.info(getCountOfUpToDateAnnots() + " annotations are up-to-date");

        log.info(unprocessed.keySet().size() + " records have been ignored");
        log.debug("The following list of keys was ignored:\n" + unprocessed.keySet().toString());
        if( invalidMP>0 ) {
            log.info(invalidMP + " incoming annotations were skipped because MP term was invalid");
        }
    }

    /**
     * Inserts an annotation into the datastore
     * @param id rgd id
     * @param evidence evidence code
     * @param accId accession id
     * @param pmId PubMed id
     * @return 0 or full annot key if new annotation inserted
     * @throws Exception
     */
    int insertOrUpdateAnnotation(RgdId id, String evidence, String accId, String pmId) throws Exception {

        Annotation annot = new Annotation();

        annot.setAnnotatedObjectRgdId(id.getRgdId());
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

        Term term = dao.getTermByAccId(accId);
        if( term!=null ) {
            annot.setTerm(term.getTerm());
            annot.setTermAcc(accId);
            annot.setAspect(aspect);
        }
        else {
            // serious problem : term not found in RGD database
            String msg = "Term " + accId + " not found in RGD database!\n"+
                    "Please reload MP ontology: (OntologyLoad/run_single.sh MP)";
            log.warn(msg);
        }

        annot.setXrefSource(pmId!=null ? "PMID:"+pmId : null);

        return insertOrUpdateAnnotation(annot);
    }

    public String getLoggerName() {
        return "status_mouse";
    }
}
