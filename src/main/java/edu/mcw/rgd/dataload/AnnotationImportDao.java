package edu.mcw.rgd.dataload;

import edu.mcw.rgd.dao.impl.AnnotationDAO;
import edu.mcw.rgd.dao.impl.OntologyXDAO;
import edu.mcw.rgd.dao.impl.RGDManagementDAO;
import edu.mcw.rgd.dao.impl.XdbIdDAO;
import edu.mcw.rgd.datamodel.RgdId;
import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

/**
 * @author mtutaj
 * @since 6/19/12
 * Centralized management of all dao code
 */
public class AnnotationImportDao {

    XdbIdDAO xdao = new XdbIdDAO();
    AnnotationDAO adao = new AnnotationDAO();
    OntologyXDAO odao = new OntologyXDAO();
    RGDManagementDAO mdao = new RGDManagementDAO();

    public String getConnectionInfo() {
        return adao.getConnectionInfo();
    }

    List<RgdId> getRGDIdsByXdbId(int xdbKey, String accId) throws Exception {
        String key = xdbKey+"|"+accId;
        List<RgdId> list = _cache.get(key);
        if( list==null ) {
            list = xdao.getRGDIdsByXdbId(xdbKey, accId);
            // filter out inactive RGD IDs
            list.removeIf(id -> !id.getObjectStatus().equals("ACTIVE"));
            _cache.put(key, list);
        }
        return list;
    }
    private final Map<String,List<RgdId>> _cache = new HashMap<>();


    List<Annotation> getAnnotationByEvidence(int rgdId, String accId, int ownerId, String evidence) throws Exception {
        return adao.getAnnotationByEvidence(rgdId, accId, ownerId, evidence);
    }

    public List<Annotation> getAnnotations(int refRgdId) throws Exception {
        return adao.getAnnotationsByReference(refRgdId);
    }

    /*
    public int getCountOfAnnotationsByReference(int refRgdId, String src) throws Exception {
        return adao.getCountOfAnnotationsByReference(refRgdId, src);
    }

    public int deleteAnnotations(List<Annotation> staleAnnots, int staleAnnotThreshold) throws Exception {

        // get to-be-deleted stale annots and check if their nr does not exceed the threshold
        Logger log = LogManager.getLogger("deleted_annots");
        if( staleAnnots.size() > staleAnnotThreshold ) {
            for( Annotation annot: staleAnnots ) {
                log.debug("TO-BE-DELETED "+annot.dump("|"));
            }
            return staleAnnots.size();
        }

        // dump all to be deleted annotation to 'deleted_annots' log
        List<Integer> fullAnnotKeys = new ArrayList<>(staleAnnots.size());
        for( Annotation annot: staleAnnots ) {
            log.debug("DELETED "+annot.dump("|"));
            fullAnnotKeys.add(annot.getKey());
        }

        // delete the annotations
        return adao.deleteAnnotations(fullAnnotKeys);
    }
*/
    public int deleteAnnotations( List<Annotation> staleAnnots ) throws Exception {

        Logger log = LogManager.getLogger("deleted_annots");

        // dump all to be deleted annotation to 'deleted_annots' log
        List<Integer> fullAnnotKeys = new ArrayList<>(staleAnnots.size());
        for( Annotation annot: staleAnnots ) {
            log.debug("DELETED "+annot.dump("|"));
            fullAnnotKeys.add(annot.getKey());
        }

        // delete the annotations
        return adao.deleteAnnotations(fullAnnotKeys);
    }

    int insertAnnotation(Annotation annot) throws Exception {
        // dump to be inserted annotation to 'inserted_annots' log
        Logger log = LogManager.getLogger("inserted_annots");
        // insert the annotation
        int r = adao.insertAnnotation(annot);
        log.debug("INSERTED "+annot.dump("|"));
        return r;
    }

    void updateAnnotation(Annotation newAnnot, Annotation oldAnnot) throws Exception {
        // dump to be inserted annotation to 'inserted_annots' log
        Logger log = LogManager.getLogger("updated_annots");
        log.debug("OLD_ANNOT: "+oldAnnot.dump("|"));
        log.debug("NEW_ANNOT: "+newAnnot.dump("|"));

        // insert the annotation
        adao.updateAnnotation(newAnnot);
    }

    Object getObject(Integer rgdId) throws Exception {

        Object obj = _objectCache.get(rgdId);
        if( obj==null ) {
            // object not in cache yet
            obj = mdao.getObject(rgdId);
            _objectCache.put(rgdId, obj);
        }
        return obj;
    }
    // private object cache to greatly reduce number of roundtrips to database server
    private Map<Integer, Object> _objectCache = new HashMap<>(1003);


    public Term getTermByAccId(String accId) throws Exception {
        return odao.getTermWithStatsCached(accId);
    }
}
