package edu.mcw.rgd.dataload;

import edu.mcw.rgd.dao.DataSourceFactory;
import edu.mcw.rgd.process.Utils;
import org.springframework.jdbc.core.SqlParameter;
import org.springframework.jdbc.object.MappingSqlQuery;

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.sql.Types;
import java.util.Set;

/**
 * @author mtutaj
 * @since 8/19/14
 * <p>
 * UMLS data for given OMIM id
 * @deprecated server with UMLS data have been retired; UMLS data service is no longer available
 */
public class UmlsData {
    private String omimId;
    private String termName;
    private String synonyms;
    private String termAcronym;
    private String meshId;
    private String def;

    private String getOmimId() {
        return omimId;
    }

    private void setOmimId(String omimId) {
        this.omimId = omimId;
    }

    private String getTermName() {
        return termName;
    }

    private void setTermName(String termName) {
        if( this.termName!=null )
            System.out.println("trying to overwrite term-name: "+termName+" for "+omimId);
        this.termName = termName;
    }

    private String getSynonyms() {
        return synonyms;
    }

    private void addSynonym(String synonym) {
        if( synonyms==null )
            synonyms = synonym;
        else
            synonyms += ", synonym";
    }

    private String getTermAcronym() {
        return termAcronym;
    }

    private void addTermAcronym(String termAcronym) {
        if( this.termAcronym==null )
            this.termAcronym = termAcronym;
        else
            this.termAcronym += ", "+termAcronym;
    }

    private String getMeshId() {
        return meshId;
    }

    private void addMeshId(String meshId) {
        if( this.meshId==null )
            this.meshId = meshId;
        else if( !this.meshId.contains(meshId) )
            this.meshId += ", "+meshId;
    }

    private String getDef() {
        return def;
    }

    private void addDef(String def) {
        if( this.def==null )
            this.def = def;
        else
            this.def += ", "+def;
    }

    private static UmlsData getUmlsDataForOmimId(String omimId) throws Exception {
        final UmlsData r = new UmlsData();
        r.setOmimId(omimId);
        String accId = omimId.substring(5); // strip 'OMIM:' from omimId
        Object[] args = new Object[]{accId};

        DataSource ds = DataSourceFactory.getInstance().getDataSource("Umls");

        // term name and acronym
        String sql = "SELECT * FROM umls_meta2.mrconso \n" +
                "WHERE sab='OMIM' AND code=? ";
        MappingSqlQuery q = new MappingSqlQuery(ds, sql) {
            protected Object mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
                String str = rs.getString("STR");
                String tty = rs.getString("TTY");
                switch (tty) {
                    case "ACR":
                        r.addTermAcronym(str);
                        break;
                    case "PT":
                        r.setTermName(str);
                        break;
                    case "SYN":
                    case "ET":
                        r.addSynonym(str);
                        break;
                    default:
                        System.out.println("Unknown TTY " + tty + ", for " + r.getOmimId());
                        break;
                }
                return null;
            }
        };
        q.declareParameter(new SqlParameter(Types.VARCHAR));
        q.execute(args);

        // mesh mappings
        sql = "SELECT code FROM umls_meta2.mrconso WHERE sab='MSH' AND cui IN "+
                "(SELECT cui FROM umls_meta2.mrconso WHERE code=? AND sab='OMIM')";
        q = new MappingSqlQuery(ds, sql) {
            protected Object mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
                r.addMeshId("MESH:"+rs.getString("code"));
                return null;
            }
        };
        q.declareParameter(new SqlParameter(Types.VARCHAR));
        q.execute(args);

        // def
        sql = "SELECT def FROM umls_meta2.mrdef WHERE cui IN (SELECT cui FROM umls_meta2.mrconso WHERE code=? AND sab='OMIM')";
        q = new MappingSqlQuery(ds, sql) {
            protected Object mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
                r.addDef(rs.getString("def"));
                return null;
            }
        };
        q.declareParameter(new SqlParameter(Types.VARCHAR));
        q.execute(args);

        return r;
    }

    private static void handleUnmappedDiseaseIds(Set<String> unmappedDiseaseIds, String unmappedDiseaseFile) throws Exception {

        BufferedWriter bw = new BufferedWriter(new FileWriter(unmappedDiseaseFile));
        bw.write("Disease Id\tDisease\tSymbols\tSynonyms\tDefinition\tMESH Id\t\n");

        for( String omimId: unmappedDiseaseIds ) {
            UmlsData data = getUmlsDataForOmimId(omimId);
            bw.write(data.getOmimId()+"\t"+
                    Utils.defaultString(data.getTermName())+"\t"+
                    Utils.defaultString(data.getTermAcronym())+"\t"+
                    Utils.defaultString(data.getSynonyms())+"\t"+
                    Utils.defaultString(data.getDef())+"\t"+
                    Utils.defaultString(data.getMeshId())+"\t"+
                    "\n"
            );
        }
        bw.close();
    }

}
