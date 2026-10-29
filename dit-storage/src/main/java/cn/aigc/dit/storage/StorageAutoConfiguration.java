package cn.aigc.dit.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageAutoConfiguration {

    /** 空闲超过该时间的连接不再复用，避免池里留下对端已失效的 TCP。 */
    private static final Duration CONNECTION_MAX_IDLE = Duration.ofSeconds(20);
    /** 连接最长存活时间；到期后下次请求换新连接。 */
    private static final Duration CONNECTION_TTL = Duration.ofSeconds(60);

    @Bean(destroyMethod = "close")
    @Conditional(S3ConfiguredCondition.class)
    public S3Client s3Client(StorageProperties properties) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.getEndpoint()))
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.isPathStyle())
                        .build())
                .httpClientBuilder(ApacheHttpClient.builder()
                        .tcpKeepAlive(true)
                        .connectionMaxIdleTime(CONNECTION_MAX_IDLE)
                        .connectionTimeToLive(CONNECTION_TTL))
                .overrideConfiguration(clientTimeouts())
                .build();
    }

    @Bean(destroyMethod = "close")
    @Conditional(S3ConfiguredCondition.class)
    public S3Presigner s3Presigner(StorageProperties properties) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(properties.presignEndpoint()))
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(credentials(properties))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.isPathStyle())
                        .build())
                .build();
    }

    @Bean
    @Conditional(S3ConfiguredCondition.class)
    public ObjectStorage objectStorage(S3Client s3Client, S3Presigner s3Presigner, StorageProperties properties) {
        return new S3ObjectStorage(s3Client, s3Presigner, properties);
    }

    @Bean
    @Conditional(S3NotConfiguredCondition.class)
    public ObjectStorage localObjectStorage() {
        return new LocalObjectStorage();
    }

    /** 对象存储无超时时，一次卡住的读会占住页面连接，删除等接口会跟着超时。 */
    private static ClientOverrideConfiguration clientTimeouts() {
        return ClientOverrideConfiguration.builder()
                .apiCallAttemptTimeout(Duration.ofSeconds(8))
                .apiCallTimeout(Duration.ofSeconds(12))
                .build();
    }

    private static StaticCredentialsProvider credentials(StorageProperties properties) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey()));
    }
}
