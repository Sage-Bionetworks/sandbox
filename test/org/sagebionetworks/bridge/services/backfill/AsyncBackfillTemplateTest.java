package org.sagebionetworks.bridge.services.backfill;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.longThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import org.hamcrest.Matcher;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.sagebionetworks.bridge.dao.BackfillDao;
import org.sagebionetworks.bridge.dao.DistributedLockDao;
import org.sagebionetworks.bridge.exceptions.ConcurrentModificationException;
import org.sagebionetworks.bridge.models.BackfillRecord;
import org.sagebionetworks.bridge.models.BackfillStatus;
import org.sagebionetworks.bridge.models.BackfillTask;

public class AsyncBackfillTemplateTest {

    @Test
    public void test() throws Exception {

        final AsyncBackfillTemplate backfillTemplate = new TestBackfillService();

        // Mock lock
        final Class<TestBackfillService> lockClazz = TestBackfillService.class;
        final String lockObject = TestBackfillService.class.getSimpleName();
        final String lock = "lock";
        final DistributedLockDao lockDao = mock(DistributedLockDao.class);
        when(lockDao.acquireLock(lockClazz, lockObject, TestBackfillService.EXPIRE)).thenReturn(lock);
        backfillTemplate.setDistributedLockDao(lockDao);

        // Mock task and backfill dao
        final String taskName = "taskName";
        final String user = "user";
        final long timestamp = DateTime.now(DateTimeZone.UTC).getMillis();
        final String taskId = "taskId";
        BackfillTask backfillTask = createBackfillTask(taskName, user, timestamp, taskId, BackfillStatus.SUBMITTED);
        BackfillDao backfillDao = mock(BackfillDao.class);
        when(backfillDao.createTask(taskName, user)).thenReturn(backfillTask);
        backfillTemplate.setBackfillDao(backfillDao);

        // Mock callback
        BackfillCallback callback = mock(BackfillCallback.class);
        backfillTemplate.backfill(user, taskName, callback);
        Thread.sleep(100L);

        // Verify callback
        verify(callback, times(1)).start(backfillTask);
        verify(callback, times(1)).newRecords(any(BackfillRecord.class));
        verify(callback, times(1)).newRecords(any(BackfillRecord.class), any(BackfillRecord.class));
        verify(callback, times(1)).done();

        // Verify lock
        verify(lockDao, times(1)).acquireLock(lockClazz, lockObject, TestBackfillService.EXPIRE);
        verify(lockDao, times(1)).releaseLock(lockClazz, lockObject, lock);

        // Verify backfill dao
        verify(backfillDao, times(1)).createTask(taskName, user);
        verify(backfillDao, times(1)).updateTaskStatus(taskId, BackfillStatus.IN_PROCESS);
        verify(backfillDao, times(1)).updateTaskStatus(taskId, BackfillStatus.COMPLETED);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testWithConcurrentModificationException() throws Exception {

        final AsyncBackfillTemplate backfillTemplate = new TestBackfillService();

        // Mock lock
        final Class<TestBackfillService> lockClazz = TestBackfillService.class;
        final String lockObject = TestBackfillService.class.getSimpleName();
        final DistributedLockDao lockDao = mock(DistributedLockDao.class);
        when(lockDao.acquireLock(lockClazz, lockObject, TestBackfillService.EXPIRE))
                .thenThrow(ConcurrentModificationException.class);
        backfillTemplate.setDistributedLockDao(lockDao);

        // Mock task and backfill dao
        final String taskName = "taskName";
        final String user = "user";
        final long timestamp = DateTime.now(DateTimeZone.UTC).getMillis();
        final String taskId1 = "taskId1";
        final BackfillTask task1 = createBackfillTask(taskName, user, timestamp, taskId1, BackfillStatus.COMPLETED);
        final String taskId2 = "taskId2";
        final BackfillTask task2 = createBackfillTask(taskName, user, timestamp, taskId2, BackfillStatus.IN_PROCESS);
        BackfillDao backfillDao = mock(BackfillDao.class);
        Answer<List<BackfillTask>> tasks = new Answer<List<BackfillTask>>() {
            @Override
            public List<BackfillTask> answer(InvocationOnMock invocation) throws Throwable {
                return Arrays.asList(task1, task2);
            }
        };
        when(backfillDao.getTasks(eq(taskName), anyLong())).thenAnswer(tasks);
        backfillTemplate.setBackfillDao(backfillDao);

        // Do backfill
        final long beforeBackfill = DateTime.now(DateTimeZone.UTC).getMillis();
        backfillTemplate.backfill(user, taskName, mock(BackfillCallback.class));
        Thread.sleep(100L);
        final long afterBackfill = DateTime.now(DateTimeZone.UTC).getMillis();

        // Verify lock
        verify(lockDao, times(1)).acquireLock(lockClazz, lockObject, TestBackfillService.EXPIRE);

        // Verify backfill dao
        Matcher<Long> sinceMatcher = new ArgumentMatcher<Long>() {
            @Override
            public boolean matches(Object item) {
                long since = ((Long)item).longValue();
                long expireInMillis = TestBackfillService.EXPIRE * 1000L;
                return beforeBackfill - expireInMillis < since && since < afterBackfill - expireInMillis;
            }
        };
        verify(backfillDao, times(1)).getTasks(eq(taskName), longThat(sinceMatcher));
        verify(backfillDao, times(1)).getRecordCount(taskId2);
    }

    private BackfillTask createBackfillTask(final String taskName, final String user, final long timestamp,
            final String taskId, final BackfillStatus status) {
        return new BackfillTask() {
            @Override
            public String getId() {
                return taskId;
            }
            @Override
            public long getTimestamp() {
                return timestamp;
            }
            @Override
            public String getName() {
                return taskName;
            }
            @Override
            public String getUser() {
                return user;
            }
            @Override
            public String getStatus() {
                return status.name();
            }
        };
    }
}
