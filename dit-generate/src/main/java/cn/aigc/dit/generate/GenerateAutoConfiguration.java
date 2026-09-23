package cn.aigc.dit.generate;

import cn.aigc.dit.generate.caption.FfmpegVideoCaptionBurner;
import cn.aigc.dit.generate.caption.AssVideoCaptionBurner;
import cn.aigc.dit.generate.caption.VideoCaptionBurner;
import cn.aigc.dit.generate.poster.FfmpegPosterExtractor;
import cn.aigc.dit.generate.poster.PosterExtractor;
import cn.aigc.dit.storage.ObjectStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GenerateProperties.class)
public class GenerateAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(PosterExtractor.class)
    public PosterExtractor posterExtractor(ObjectStorage objectStorage) {
        return new FfmpegPosterExtractor(objectStorage);
    }

    @Bean
    @ConditionalOnMissingBean(VideoCaptionBurner.class)
    public VideoCaptionBurner videoCaptionBurner(ObjectStorage objectStorage, GenerateProperties properties) {
        return new FfmpegVideoCaptionBurner(objectStorage, properties);
    }

    @Bean
    @ConditionalOnMissingBean(AssVideoCaptionBurner.class)
    public AssVideoCaptionBurner assVideoCaptionBurner(ObjectStorage objectStorage, GenerateProperties properties) {
        return new AssVideoCaptionBurner(objectStorage, properties);
    }
}
