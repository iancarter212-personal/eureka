package com.netflix.eureka.test;

/**
 * JUnit 4 category marker interface for slow-running tests.
 * Tests annotated with {@code @Category(SlowTests.class)} are excluded
 * from the default CI build and run on a nightly schedule instead.
 */
public interface SlowTests {
}
