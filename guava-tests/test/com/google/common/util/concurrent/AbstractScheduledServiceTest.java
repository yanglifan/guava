/*
 * Copyright (C) 2011 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.util.concurrent;

import com.google.common.util.concurrent.AbstractScheduledService.Scheduler;

import junit.framework.TestCase;

import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit test for {@link AbstractScheduledService}.
 *
 * @author Luke Sandberg
 */

public class AbstractScheduledServiceTest extends TestCase {

  volatile Scheduler configuration = Scheduler.newFixedDelaySchedule(0, 10, TimeUnit.MILLISECONDS);
  volatile ScheduledFuture<?> future = null;

  volatile boolean atFixedRateCalled = false;
  volatile boolean withFixedDelayCalled = false;
  volatile boolean scheduleCalled = false;

  final ScheduledExecutorService executor = new ScheduledThreadPoolExecutor(10) {
    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
        long delay, TimeUnit unit) {
      return future = super.scheduleWithFixedDelay(command, initialDelay, delay, unit);
    }
  };

  public void testServiceStartStop() throws Exception {
    NullService service = new NullService();
    service.startAndWait();
    assertFalse(future.isDone());
    service.stopAndWait();
    assertTrue(future.isCancelled());
  }

  private class NullService extends AbstractScheduledService {
    @Override protected void runOneIteration() throws Exception { }
    @Override protected void startUp() throws Exception { }
    @Override protected void shutDown() throws Exception { }
    @Override protected Scheduler scheduler() { return configuration; }
    @Override protected ScheduledExecutorService executor() { return executor; }
  }

  public void testFailOnExceptionFromRun() throws Exception {
    TestService service = new TestService();
    service.runException = new Exception();
    service.startAndWait();
    service.runFirstBarrier.await();
    service.runSecondBarrier.await();
    try {
      future.get();
      fail();
    } catch (ExecutionException e) {
      // An execution exception holds a runtime exception (from throwables.propogate) that holds our
      // original exception.
      assertEquals(service.runException, e.getCause().getCause());
    }
    assertEquals(service.state(), Service.State.FAILED);
  }

  public void testFailOnExceptionFromStartUp() {
    TestService service = new TestService();
    service.startUpException = new Exception();
    try {
      service.startAndWait();
      fail();
    } catch (UncheckedExecutionException e) {
      assertEquals(service.startUpException, e.getCause());
    }
    assertEquals(0, service.numberOfTimesRunCalled.get());
    assertEquals(Service.State.FAILED, service.state());
  }

  public void testFailOnExceptionFromShutDown() throws Exception {
    TestService service = new TestService();
    service.shutDownException = new Exception();
    service.startAndWait();
    service.runFirstBarrier.await();
    ListenableFuture<Service.State> stopHandle = service.stop();
    service.runSecondBarrier.await();
    try {
      stopHandle.get();
      fail();
    } catch (ExecutionException e) {
      assertEquals(service.shutDownException, e.getCause());
    }
    assertEquals(Service.State.FAILED, service.state());
  }

  public void testRunOneIterationCalledMultipleTimes() throws Exception {
    TestService service = new TestService();
    service.startAndWait();
    for (int i = 1; i < 10; i++) {
      service.runFirstBarrier.await();
      assertEquals(i, service.numberOfTimesRunCalled.get());
      service.runSecondBarrier.await();
    }
    service.runFirstBarrier.await();
    service.stop();
    service.runSecondBarrier.await();
    service.stopAndWait();
  }

  public void testExecutorOnlyCalledOnce() throws Exception {
    TestService service = new TestService();
    service.startAndWait();
    // It should be called once during startup.
    assertEquals(1, service.numberOfTimesExecutorCalled.get());
    for (int i = 1; i < 10; i++) {
      service.runFirstBarrier.await();
      assertEquals(i, service.numberOfTimesRunCalled.get());
      service.runSecondBarrier.await();
    }
    service.runFirstBarrier.await();
    service.stop();
    service.runSecondBarrier.await();
    service.stopAndWait();
    // Only called once overall.
    assertEquals(1, service.numberOfTimesExecutorCalled.get());
  }

  public void testSchedulerOnlyCalledOnce() throws Exception {
    TestService service = new TestService();
    service.startAndWait();
    // It should be called once during startup.
    assertEquals(1, service.numberOfTimesSchedulerCalled.get());
    for (int i = 1; i < 10; i++) {
      service.runFirstBarrier.await();
      assertEquals(i, service.numberOfTimesRunCalled.get());
      service.runSecondBarrier.await();
    }
    service.runFirstBarrier.await();
    service.stop();
    service.runSecondBarrier.await();
    service.stopAndWait();
    // Only called once overall.
    assertEquals(1, service.numberOfTimesSchedulerCalled.get());
  }

  private class TestService extends AbstractScheduledService {
    CyclicBarrier runFirstBarrier = new CyclicBarrier(2);
    CyclicBarrier runSecondBarrier = new CyclicBarrier(2);

    volatile boolean startUpCalled = false;
    volatile boolean shutDownCalled = false;
    AtomicInteger numberOfTimesRunCalled = new AtomicInteger(0);
    AtomicInteger numberOfTimesExecutorCalled = new AtomicInteger(0);
    AtomicInteger numberOfTimesSchedulerCalled = new AtomicInteger(0);
    volatile Exception runException = null;
    volatile Exception startUpException = null;
    volatile Exception shutDownException = null;

    @Override
    protected void runOneIteration() throws Exception {
      assertTrue(startUpCalled);
      assertFalse(shutDownCalled);
      numberOfTimesRunCalled.incrementAndGet();
      assertEquals(State.RUNNING, state());
      runFirstBarrier.await();
      runSecondBarrier.await();
      if (runException != null) {
        throw runException;
      }
    }

    @Override
    protected void startUp() throws Exception {
      assertFalse(startUpCalled);
      assertFalse(shutDownCalled);
      startUpCalled = true;
      assertEquals(State.STARTING, state());
      if (startUpException != null) {
        throw startUpException;
      }
    }

    @Override
    protected void shutDown() throws Exception {
      assertTrue(startUpCalled);
      assertFalse(shutDownCalled);
      shutDownCalled = true;
      if (shutDownException != null) {
        throw shutDownException;
      }
    }

    @Override
    protected ScheduledExecutorService executor() {
      numberOfTimesExecutorCalled.incrementAndGet();
      return executor;
    }

    @Override
    protected Scheduler scheduler() {
      numberOfTimesSchedulerCalled.incrementAndGet();
      return configuration;
    }
  }

  public static class SchedulerTest extends TestCase {
    // These constants are arbitrary and just used to make sure that the correct method is called
    // with the correct parameters.
    private static final int initialDelay = 10;
    private static final int delay = 20;
    private static final TimeUnit unit = TimeUnit.MILLISECONDS;

    // Unique runnable object used for comparison.
    final Runnable testRunnable = new Runnable() {@Override public void run() {}};
    boolean called = false;

    private void assertSingleCallWithCorrectParameters(Runnable command, long initialDelay,
        long delay, TimeUnit unit) {
      assertFalse(called);  // only called once.
      called = true;
      assertEquals(SchedulerTest.initialDelay, initialDelay);
      assertEquals(SchedulerTest.delay, delay);
      assertEquals(SchedulerTest.unit, unit);
      assertEquals(testRunnable, command);
    }

    public void testFixedRateSchedule() {
      Scheduler schedule = Scheduler.newFixedRateSchedule(initialDelay, delay, unit);
      schedule.schedule(new ScheduledThreadPoolExecutor(1) {
        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay,
            long period, TimeUnit unit) {
          assertSingleCallWithCorrectParameters(command, initialDelay, delay, unit);
          return null;
        }
      }, testRunnable);
      assertTrue(called);
    }

    public void testFixedDelaySchedule() {
      Scheduler schedule = Scheduler.newFixedDelaySchedule(initialDelay, delay, unit);
      schedule.schedule(new ScheduledThreadPoolExecutor(10) {
        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
            long delay, TimeUnit unit) {
          assertSingleCallWithCorrectParameters(command, initialDelay, delay, unit);
          return null;
        }
      }, testRunnable);
      assertTrue(called);
    }

    public void testCustomSchedule_startStop() throws Exception {
      final CyclicBarrier firstBarrier = new CyclicBarrier(2);
      final CyclicBarrier secondBarrier = new CyclicBarrier(2);
      final AtomicBoolean shouldWait = new AtomicBoolean(true);
      Runnable task = new Runnable() {
        @Override public void run() {
          try {
            if (shouldWait.get()) {
              firstBarrier.await();
              secondBarrier.await();
            }
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
      };
      TestCustomScheduler scheduler = new TestCustomScheduler();
      Future<?> future = scheduler.schedule(Executors.newScheduledThreadPool(10), task);
      firstBarrier.await();
      assertEquals(1, scheduler.scheduleCounter.get());
      secondBarrier.await();
      firstBarrier.await();
      assertEquals(2, scheduler.scheduleCounter.get());
      shouldWait.set(false);
      secondBarrier.await();
      future.cancel(false);
    }

    private class TestCustomScheduler extends AbstractScheduledService.CustomScheduler {
      public AtomicInteger scheduleCounter = new AtomicInteger(0);
      @Override
      protected Future<Void> scheduleNextIteration(ScheduledExecutorService service,
          Callable<Void> callable) {
        scheduleCounter.incrementAndGet();
        return service.schedule(callable, 0, TimeUnit.SECONDS);
      }
    }

    public void testCustomSchedulerServiceStop() throws Exception {
      TestAbstractScheduledCustomService service = new TestAbstractScheduledCustomService();
      service.startAndWait();
      service.firstBarrier.await();
      assertEquals(1, service.numIterations.get());
      service.stop();
      service.secondBarrier.await();
      service.stopAndWait();
      // Sleep for a while just to ensure that our task wasn't called again.
      Thread.sleep(unit.toMillis(3 * delay));
      assertEquals(1, service.numIterations.get());
    }

    public void testBig() throws Exception {
      TestAbstractScheduledCustomService service = new TestAbstractScheduledCustomService() {
        @Override protected Scheduler scheduler() {
          return new AbstractScheduledService.CustomScheduler(){
            @Override
            protected Future<Void> scheduleNextIteration(ScheduledExecutorService service,
                Callable<Void> callable) {
              final ScheduledFuture<Void> future = service.schedule(callable, 0, TimeUnit.SECONDS);
              // Explicitly yield to increase the probability of a pathological scheduling.
              Thread.yield();
              return future;
            }
          };
        }
      };
      service.useBarriers = false;
      service.startAndWait();
      Thread.sleep(50);
      service.useBarriers = true;
      service.firstBarrier.await();
      int numIterations = service.numIterations.get();
      service.stop();
      service.secondBarrier.await();
      service.stopAndWait();
      assertEquals(numIterations, service.numIterations.get());
    }

    private static class TestAbstractScheduledCustomService extends AbstractScheduledService {
      final AtomicInteger numIterations = new AtomicInteger(0);
      volatile boolean useBarriers = true;
      final CyclicBarrier firstBarrier = new CyclicBarrier(2);
      final CyclicBarrier secondBarrier = new CyclicBarrier(2);

      @Override protected void runOneIteration() throws Exception {
        numIterations.incrementAndGet();
        if (useBarriers) {
          firstBarrier.await();
          secondBarrier.await();
        }
      }

      @Override protected ScheduledExecutorService executor() {
        // use a bunch of threads so that weird overlapping schedules are more likely to happen.
        return Executors.newScheduledThreadPool(10);
      }

      @Override protected void startUp() throws Exception { }

      @Override protected void shutDown() throws Exception { }

      @Override protected Scheduler scheduler() {
        return new CustomScheduler() {
          @Override
          protected Future<Void> scheduleNextIteration(ScheduledExecutorService service,
              Callable<Void> callable) {
            return service.schedule(callable, delay, unit);
          }};
      }
    }
  }
}