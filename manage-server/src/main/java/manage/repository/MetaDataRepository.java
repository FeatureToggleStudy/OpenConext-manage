package manage.repository;

import manage.model.EntityType;
import manage.model.MetaData;
import manage.mongo.Sequence;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

/**
 * We can't use the Spring JPA repositories as we at runtime need to decide which collection to use. We only have one
 * Document type - e.g. MetaData - and more then one MetaData collections.
 */
@Repository
public class MetaDataRepository {

    private static final int AUTOCOMPLETE_LIMIT = 16;

    private MongoTemplate mongoTemplate;
    private List<String> supportedLanguages;

    @Autowired
    public MetaDataRepository(MongoTemplate mongoTemplate,
                              @Value("${product.supported_languages}") String supportedLanguages) {
        this.mongoTemplate = mongoTemplate;
        this.supportedLanguages = Stream.of(supportedLanguages.split(",")).map(String::trim).collect(toList());
    }

    public MetaData findById(String id, String type) {
        return mongoTemplate.findById(id, MetaData.class, type);
    }

    public MetaData save(MetaData metaData) {
        mongoTemplate.insert(metaData, metaData.getType());
        return metaData;
    }

    public void remove(MetaData metaData) {
        mongoTemplate.remove(metaData, metaData.getType());
    }

    public List<MetaData> revisions(String type, String parentId) {
        Query query = new Query(Criteria.where("revision.parentId").is(parentId));
        return mongoTemplate.find(query, MetaData.class, type);
    }

    public void update(MetaData metaData) {
        mongoTemplate.save(metaData, metaData.getType());
    }

    public MongoTemplate getMongoTemplate() {
        return mongoTemplate;
    }

    public List<Map> autoComplete(String type, String search) {
        Query query = queryWithSamlFields();
        if ("*".equals(search)) {
            return mongoTemplate.find(query, Map.class, type);
        }
        String escapedSearch = escapeSpecialChars(search);
        query.limit(AUTOCOMPLETE_LIMIT);
        Criteria criteria = new Criteria();
        List<Criteria> orCriterias = new ArrayList<>();
        orCriterias.add(regex("data.entityid", search));
        this.supportedLanguages.forEach(lang -> {
            orCriterias.add(regex("data.metaDataFields.name:" + lang, escapedSearch));
            orCriterias.add(regex("data.keywords.name:" + lang, escapedSearch));
        });
        query.addCriteria(criteria.orOperator(orCriterias.toArray(new Criteria[orCriterias.size()])));
        return mongoTemplate.find(query, Map.class, type);
    }

    public List<Map> allServiceProviderEntityIds() {
        Query query = new Query();
        query
                .fields()
                .include("data.entityid")
                .include("data.metaDataFields.name:en")
                .include("data.metaDataFields.coin:imported_from_edugain")
                .include("data.metaDataFields.coin:publish_in_edugain");
        return mongoTemplate.find(query, Map.class, EntityType.SP.getType());
    }

    public int deleteAllImportedServiceProviders() {
        Query query = new Query(Criteria.where("data.metaDataFields.coin:imported_from_edugain").is(true));
        return mongoTemplate.remove(query, EntityType.SP.getType()).getN();
    }

    public long countAllImportedServiceProviders() {
        Query query = new Query(Criteria.where("data.metaDataFields.coin:imported_from_edugain").is(true));
        return mongoTemplate.count(query, EntityType.SP.getType());
    }


    protected String escapeSpecialChars(String query) {
        return query.replaceAll("([\\Q\\/$^.?*+{}()|[]\\E])", "\\\\$1");
    }

    private Criteria regex(String key, String search) {
        try {
            return Criteria.where(key).regex(".*" + search + ".*", "i");
        } catch (IllegalArgumentException e) {
            //ignore
            return Criteria.where("nope").exists(true);
        }
    }

    private String escapeMetaDataField(String key) {
        if (key.startsWith("metaDataFields")) {
            return "metaDataFields." + key.substring("metaDataFields.".length())
                    .replaceAll("\\.", "@");
        }
        if (key.startsWith("arp.attributes")) {
            return "arp.attributes." + key.substring("arp.attributes.".length())
                    .replaceAll("\\.", "@");
        }
        return key;

    }

    public List<Map> search(String type, Map<String, Object> properties, List<String> requestedAttributes, Boolean
            allAttributes, Boolean logicalOperatorIsAnd) {
        Query query = allAttributes ? new Query() : queryWithSamlFields();
        if (!allAttributes) {
            requestedAttributes.forEach(requestedAttribute -> {
                String key = escapeMetaDataField(requestedAttribute);
                query.fields().include("data.".concat(key));
            });
            if (type.contains("revision")) {
                query.fields().include("revision").include("data.revisionnote");
            }
        }
        List<CriteriaDefinition> criteriaDefinitions = new ArrayList<>();

        properties.forEach((key, value) -> {
            key = escapeMetaDataField(key);

            if (value instanceof Boolean && (Boolean) value && key.contains("attributes")) {
                criteriaDefinitions.add(Criteria.where("data.".concat(key)).exists(true));
            } else if (value instanceof String && !StringUtils.hasText((String) value)) {
                criteriaDefinitions.add(Criteria.where("data.".concat(key)).exists(false));
            } else if (value instanceof String && StringUtils.hasText((String) value) &&
                    ("true".equalsIgnoreCase((String)value) || "false".equalsIgnoreCase((String)value))) {
                criteriaDefinitions.add(Criteria.where("data.".concat(key)).is(Boolean.parseBoolean((String) value)));
            } else if (value instanceof String && StringUtils.hasText((String) value) && isNumeric((String) value)) {
                criteriaDefinitions.add(Criteria.where("data.".concat(key)).is(Integer.parseInt((String) value)));
            } else if ("*".equals(value)) {
                criteriaDefinitions.add(Criteria.where("data.".concat(key)).regex(".*", "i"));
            } else if (value instanceof String && ((String) value).contains("*")) {
                String queryString = (String) value;
                criteriaDefinitions.add(Criteria.where("data.".concat(key)).regex(queryString, "i"));
            } else if (value instanceof List && !((List) value).isEmpty()) {
                List l = (List) value;
                criteriaDefinitions.add(Criteria.where("data.".concat(key)).in(l));
            } else {
                criteriaDefinitions.add(Criteria.where("data.".concat(key)).is(value));
            }
        });
        if (criteriaDefinitions.isEmpty()) {
            criteriaDefinitions.add(Criteria.where("data").exists(true));
        }
        Criteria[] criteria = criteriaDefinitions.toArray(new Criteria[]{});
        if (logicalOperatorIsAnd) {
            query.addCriteria(new Criteria().andOperator(criteria));
        } else {
            query.addCriteria(new Criteria().orOperator(criteria));
        }
        return mongoTemplate.find(query, Map.class, type);
    }

    private boolean isNumeric(String value) {
        return value.matches("\\d+");
    }

    public List<MetaData> findRaw(String type, String query) {
        return mongoTemplate.find(new BasicQuery(query), MetaData.class, type);
    }

    public List<Map> whiteListing(String type, String state) {
        Query query = queryWithSamlFields().addCriteria(Criteria.where("data.state").is(state));
        query.fields()
                .include("data.allowedall")
                .include("data.allowedEntities")
                .include("data.metaDataFields.coin:stepup:requireloa");
        List<Map> metaData = mongoTemplate.find(query, Map.class, type);
        if (type.equals(EntityType.SP.getType())) {
            List<Map> oidcMetaData = mongoTemplate.find(query, Map.class, EntityType.RP.getType());
            metaData.addAll(oidcMetaData);
        }
        return metaData;
    }

    public Long incrementEid() {
        Update updateInc = new Update();
        updateInc.inc("value", 1L);
        Sequence res = mongoTemplate.findAndModify(new BasicQuery("{\"_id\":\"sequence\"}"), updateInc, Sequence.class);
        return res.getValue();
    }

    private Query queryWithSamlFields() {
        Query query = new Query();
        //When we have multiple types then we need to delegate depending on the type.
        Field fields = query
                .fields();
        fields
                .include("version")
                .include("type")
                .include("data.state")
                .include("data.entityid")
                .include("data.notes");
        this.supportedLanguages.forEach(lang -> fields.include("data.metaDataFields.name:" + lang));
        return query;
    }
}
