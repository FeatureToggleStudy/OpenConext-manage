package manage.mongo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mongobee.Mongobee;
import com.github.mongobee.changeset.ChangeLog;
import com.github.mongobee.changeset.ChangeSet;
import manage.conf.IndexConfiguration;
import manage.conf.MetaDataAutoConfiguration;
import manage.model.MetaData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexDefinition;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Configuration
@ChangeLog
public class MongobeeConfiguration {

    public static final String REVISION_POSTFIX = "_revision";
    private static MetaDataAutoConfiguration staticMetaDataAutoConfiguration;
    @Autowired
    private MetaDataAutoConfiguration metaDataAutoConfiguration;
    @Autowired
    private MappingMongoConverter mongoConverter;

    // Converts . into a mongo friendly char
    @PostConstruct
    public void setUpMongoEscapeCharacterConversion() {
        mongoConverter.setMapKeyDotReplacement("@");
    }

    @Bean
    public Mongobee mongobee(@Value("${spring.data.mongodb.uri}") String uri) {
        Mongobee runner = new Mongobee(uri);
        runner.setChangeLogsScanPackage("manage.mongo");
        MongobeeConfiguration.staticMetaDataAutoConfiguration = metaDataAutoConfiguration;
        return runner;
    }

    @ChangeSet(order = "001", id = "createCollections", author = "Okke Harsta", runAlways = true)
    public void createCollections(MongoTemplate mongoTemplate) {
        Set<String> schemaNames = staticMetaDataAutoConfiguration.schemaNames();
        schemaNames.forEach(schema -> {
            if (!mongoTemplate.collectionExists(schema)) {
                mongoTemplate.createCollection(schema);
                staticMetaDataAutoConfiguration.indexConfigurations(schema).stream()
                    .map(this::indexDefinition)
                    .forEach(mongoTemplate.indexOps(schema)::ensureIndex);

                String revision = schema.concat(REVISION_POSTFIX);
                mongoTemplate.createCollection(revision);
                mongoTemplate.indexOps(revision).ensureIndex(new Index("revision.parentId", Sort.Direction.ASC));
            }
        });
    }

    @ChangeSet(order = "002", id = "createSingleTenantTemplates", author = "Okke Harsta")
    public void createSingleTenantTemplates(MongoTemplate mongoTemplate) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:single_tenant_templates/*.json");
        Arrays.asList(resources).forEach(res -> this.createSingleTenantTemplate(mongoTemplate, res, objectMapper));

    }

    @SuppressWarnings("unchecked")
    private void createSingleTenantTemplate(MongoTemplate mongoTemplate, Resource resource, ObjectMapper objectMapper) {
        final Map<String, Object> template = readTemplate(resource, objectMapper);
        Map<String, Object> data = new HashMap<>();
        data.put("entityid", template.get("entityid"));
        data.put("state", template.get("workflowState"));
        data.put("arp", new HashMap<String, Object>());
        boolean enabled = template.containsKey("attributes");
        data.put("enabled", new HashMap<String, Object>());
        Map<String, List<Map<String, Object>>> attributes = new HashMap<>();
        if (enabled) {
            Map<String, Object> source = new HashMap<>();
            source.put("source", "idp");
            source.put("value", "*");
            List<Map<String, Object>> values = Collections.singletonList(source);
            List.class.cast(template.get("attributes")).forEach(attr -> attributes.put(((String) attr)
                .replaceAll("\\.", "@"), values));

        }
        data.put("attributes", attributes);

        Arrays.asList(new String[]{"entityid", "workflowState", "attributes"}).forEach(none -> template.remove(none));
        template.keySet().forEach(key -> {
            if (key.contains(".")) {
                template.put(key.replaceAll("\\.", "@"), template.get(key));
                template.remove(key);
            }
        });
        data.put("metaDataFields", template);

        MetaData metaData = new MetaData("single_tenant_template", data);
        metaData.initial(UUID.randomUUID().toString(), "auto-generated");
        mongoTemplate.insert(metaData, metaData.getType());
    }

    private Map<String, Object> readTemplate(Resource resource, ObjectMapper objectMapper) {
        Map<String, Object> template;
        try {
            template = objectMapper.readValue(resource.getInputStream(), Map.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return template;
    }

    private IndexDefinition indexDefinition(IndexConfiguration indexConfiguration) {
        Index index = new Index();
        indexConfiguration.getFields().forEach(field -> index.on("data.".concat(field), Sort.Direction.ASC));
        index.named(indexConfiguration.getName());
        if (indexConfiguration.isUnique()) {
            index.unique();
        }
        return index;
    }

}
