package io.github.dlrkdgml0716.cachelab.cache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class SubscriptionConfiguration {
    @Bean
    public RedisMessageListenerContainer subscriber(RedisConnectionFactory factory, InvalidationListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(listener, new ChannelTopic(HybridCache.CHANNEL));
        return container;
    }
}
