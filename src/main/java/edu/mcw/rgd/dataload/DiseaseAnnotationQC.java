package edu.mcw.rgd.dataload;

import edu.mcw.rgd.datamodel.ontology.Annotation;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import org.apache.log4j.Logger;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Created by IntelliJ IDEA.
 * User: mtutaj
 * Date: 6/3/14
 * Time: 3:23 PM
 * <p>
 * check how many prospective disease annotations as generated from HPO-to-Gene-to-Disease file
 * are present in RGD
 */
public class DiseaseAnnotationQC {

    AnnotationImportDao dao;

    Set<String> diseaseToRdoExactMatches = new HashSet<>();
    Set<String> diseaseToRdoNoMatches = new HashSet<>();
    Set<String> diseaseToRdoMultiMatches = new HashSet<>();

    int noDiseaseToRdo;
    int noRdoAnnot;
    int singleRdoAnnot;
    int multiRdoAnnot;

    public Term qc(int geneRgdId, String diseaseId) throws Exception {

        Term term = getRdoTerm(diseaseId);
        if( term==null ) {
            noDiseaseToRdo++;
            return null;
        }

        /* currently this code is suppressed for performance reasons

        List<Annotation> annots = getDiseaseAnnotations(geneRgdId, term.getAccId());
        if( annots.isEmpty() )
            noRdoAnnot++;
        else if( annots.size()==1 )
            singleRdoAnnot++;
        else {
            multiRdoAnnot++;
        }
        */
        return term;
    }

    List<Annotation> getDiseaseAnnotations(int geneRgdId, String diseaseId) throws Exception {

        List<Annotation> annots = dao.getAnnotations(geneRgdId, diseaseId);
        Iterator<Annotation> it = annots.iterator();
        while( it.hasNext() ) {
            Annotation a = it.next();
            if( !a.getDataSrc().equals("OMIM") )
                it.remove();
        }
        return annots;
    }

    Term getRdoTerm(String diseaseId) throws Exception {

        List<Term> rdoTerms = dao.getRdoTermsBySynonym(diseaseId);

        // drop obsolete terms
        Iterator<Term> it = rdoTerms.iterator();
        while( it.hasNext() ) {
            if( it.next().isObsolete() )
                it.remove();
        }

        if( rdoTerms.isEmpty() ) {
            diseaseToRdoNoMatches.add(diseaseId);
            return null;
        }
        if( rdoTerms.size()>1 ) {
            diseaseToRdoMultiMatches.add(diseaseId);
        } else {
            diseaseToRdoExactMatches.add(diseaseId);
        }
        return rdoTerms.get(0);
    }

    void dumpSummary(Logger log) {

        log.info("diseaseToRdo mappings: no match    "+diseaseToRdoNoMatches.size());
        log.info("diseaseToRdo mappings: multi match "+diseaseToRdoMultiMatches.size());
        log.info("diseaseToRdo mappings: exact match "+diseaseToRdoExactMatches.size());
        log.info("");
        log.info("  no disease to RDO mappings "+noDiseaseToRdo);
        log.info("  no disease annotations in rgd "+noRdoAnnot);
        log.info("  single disease annotation in rgd "+singleRdoAnnot);
        log.info("  multiple disease annotations in rgd "+multiRdoAnnot);

        System.out.println("diseaseToRdoNoMatches");
        System.out.println(diseaseToRdoNoMatches);
        System.out.println("diseaseToRdoMultiMatches");
        System.out.println(diseaseToRdoMultiMatches);
    }

    public AnnotationImportDao getDao() {
        return dao;
    }

    public void setDao(AnnotationImportDao dao) {
        this.dao = dao;
    }
}
