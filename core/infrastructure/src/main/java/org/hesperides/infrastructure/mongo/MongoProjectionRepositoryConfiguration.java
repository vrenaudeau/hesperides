package org.hesperides.infrastructure.mongo;

import com.mongodb.Mongo;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.validation.annotation.Validated;

import static org.hesperides.commons.spring.SpringProfiles.MONGO;

@Configuration
@Profile(MONGO)
@EnableTransactionManagement
@EnableMongoRepositories(basePackages = "org.hesperides.infrastructure.mongo")
@Getter
@Setter
@Validated
@ConfigurationProperties("projection_repository")
public class MongoProjectionRepositoryConfiguration {

    @NotNull
    private String uri;

    @Bean
    public MongoTemplate mongoTemplate(MongoClientURI uri) {
        return new MongoTemplate(mongo(uri), uri.getDatabase());
    }

    @Bean
    public Mongo mongo(MongoClientURI uri) {
        return new MongoClient(uri);
    }

    @Bean
    public MongoClientURI uri() {
        return new MongoClientURI(uri);
    }
}
