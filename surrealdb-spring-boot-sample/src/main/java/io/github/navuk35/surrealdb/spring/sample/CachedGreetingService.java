package io.github.navuk35.surrealdb.spring.sample;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

@Service
public class CachedGreetingService {

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger nicknameCalls = new AtomicInteger();
    private final AtomicInteger profileCalls = new AtomicInteger();

    @Cacheable("greetings")
    public String greet(String name) {
        calls.incrementAndGet();
        return "Hello, " + name;
    }

    @Cacheable("nicknames")
    public String findNickname(String name) {
        nicknameCalls.incrementAndGet();
        return null;
    }

    @Cacheable("profiles")
    public Greeting greetProfile(String name) {
        profileCalls.incrementAndGet();
        return new Greeting(name, "Hello, " + name);
    }

    @CacheEvict("greetings")
    public void forget(String name) {
    }

    int callCount() {
        return calls.get();
    }

    int nicknameCallCount() {
        return nicknameCalls.get();
    }

    int profileCallCount() {
        return profileCalls.get();
    }
}
