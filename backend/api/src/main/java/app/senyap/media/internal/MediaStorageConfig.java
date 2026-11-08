package app.senyap.media.internal;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
class MediaStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(MediaStorageConfig.class);

    @Bean(destroyMethod = "close")
    S3Client mediaS3Client(MediaStorageProperties props) {
        var builder = S3Client.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.accessKey(), props.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(props.pathStyle()).build());
        if (props.endpoint() != null && !props.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(props.endpoint()));
        }
        S3Client client = builder.build();
        if (props.createBucket()) {
            try {
                client.headBucket(b -> b.bucket(props.bucket()));
            } catch (NoSuchBucketException e) {
                client.createBucket(b -> b.bucket(props.bucket()));
                log.info("Bucket media {} dibuat", props.bucket());
            }
            // Lokal/test saja: objek di bawah public/ (hasil pipeline + galeri) bisa dibaca tanpa login,
            // seperti CDN di produksi. Original upload (uploads/) tetap privat.
            client.putBucketPolicy(b -> b.bucket(props.bucket()).policy(publicReadPolicy(props.bucket())));
        }
        return client;
    }

    @Bean(destroyMethod = "close")
    S3Presigner mediaS3Presigner(MediaStorageProperties props) {
        var builder = S3Presigner.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.accessKey(), props.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(props.pathStyle()).build());
        if (props.endpoint() != null && !props.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(props.endpoint()));
        }
        return builder.build();
    }

    static String publicReadPolicy(String bucket) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},\
                "Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/public/*"]}]}""".formatted(bucket);
    }

    @Bean
    HealthIndicator mediaStorageHealthIndicator(S3Client mediaS3Client, MediaStorageProperties props) {
        return new AbstractHealthIndicator() {
            @Override
            protected void doHealthCheck(Health.Builder builder) {
                mediaS3Client.headBucket(b -> b.bucket(props.bucket()));
                builder.up().withDetail("bucket", props.bucket());
            }
        };
    }
}
