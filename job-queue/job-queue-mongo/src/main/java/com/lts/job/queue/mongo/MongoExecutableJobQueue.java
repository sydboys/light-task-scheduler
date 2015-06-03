package com.lts.job.queue.mongo;

import com.google.code.morphia.query.Query;
import com.google.code.morphia.query.UpdateOperations;
import com.lts.job.core.cluster.Config;
import com.lts.job.core.logger.Logger;
import com.lts.job.core.logger.LoggerFactory;
import com.lts.job.core.util.CollectionUtils;
import com.lts.job.core.util.DateUtils;
import com.lts.job.core.util.JobQueueUtils;
import com.lts.job.queue.ExecutableJobQueue;
import com.lts.job.queue.domain.JobFeedbackPo;
import com.lts.job.queue.domain.JobPo;
import com.lts.job.queue.exception.DuplicateJobException;
import com.lts.job.store.mongo.MongoRepository;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.MongoException;
import com.mongodb.WriteResult;

import java.util.List;

/**
 * @author Robert HG (254963746@qq.com) on 5/28/15.
 */
public class MongoExecutableJobQueue extends MongoRepository implements ExecutableJobQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger(MongoExecutableJobQueue.class);

    public MongoExecutableJobQueue(Config config) {
        super(config);
    }

    @Override
    public boolean createQueue(String taskTrackerNodeGroup) {
        String tableName = JobQueueUtils.getExecutableQueueName(taskTrackerNodeGroup);
        DBCollection dbCollection = template.getCollection(tableName);
        List<DBObject> indexInfo = dbCollection.getIndexInfo();
        // create index if not exist
        if (CollectionUtils.isEmpty(indexInfo)) {
            template.ensureIndex(tableName, "idx_jobId", "jobId", true, true);
            template.ensureIndex(tableName, "idx_taskTrackerNodeGroup_taskId", "taskTrackerNodeGroup,taskId", true, true);
            template.ensureIndex(tableName, "idx_taskTrackerIdentity", "taskTrackerIdentity");
            template.ensureIndex(tableName, "idx_triggerTime_priority_gmtCreated", "triggerTime,priority,gmtCreated");
            template.ensureIndex(tableName, "idx_isRunning", "isRunning");
            LOGGER.info("create queue " + tableName);
        }
        return true;
    }

    @Override
    public boolean add(JobPo jobPo) {
        try {
            String tableName = JobQueueUtils.getExecutableQueueName(jobPo.getTaskTrackerNodeGroup());
            jobPo.setGmtCreated(DateUtils.currentTimeMillis());
            jobPo.setGmtModified(jobPo.getGmtCreated());
            template.save(tableName, jobPo);
        } catch (MongoException.DuplicateKey e) {
            // 已经存在
            throw new DuplicateJobException(e);
        }
        return true;
    }

    @Override
    public JobPo take(String taskTrackerNodeGroup, String taskTrackerIdentity) {
        String tableName = JobQueueUtils.getExecutableQueueName(taskTrackerNodeGroup);
        Query<JobPo> query = template.createQuery(tableName, JobPo.class);
        query.field("isRunning").equal(false)
                .filter("triggerTime < ", System.currentTimeMillis())
                .order(" triggerTime, priority , gmtCreated");

        UpdateOperations<JobPo> operations =
                template.createUpdateOperations(JobPo.class)
                        .set("isRunning", true)
                        .set("taskTrackerIdentity", taskTrackerIdentity)
                        .set("gmtModified", System.currentTimeMillis());

        return template.findAndModify(query, operations, false);
    }

    @Override
    public boolean remove(String taskTrackerNodeGroup, String jobId) {
        String tableName = JobQueueUtils.getExecutableQueueName(taskTrackerNodeGroup);
        Query<JobPo> query = template.createQuery(tableName, JobPo.class);
        query.field("jobId").equal(jobId);
        WriteResult wr = template.delete(query);
        return wr.getN() == 1;
    }

    public void resume(JobPo jobPo) {
        String tableName = JobQueueUtils.getExecutableQueueName(jobPo.getTaskTrackerNodeGroup());
        Query<JobPo> query = template.createQuery(tableName, JobPo.class);

        query.field("jobId").equal(jobPo.getJobId());

        UpdateOperations<JobPo> operations =
                template.createUpdateOperations(JobPo.class)
                        .set("isRunning", false)
                        .set("taskTrackerIdentity", "")
                        .set("gmtModified", System.currentTimeMillis());
        template.update(query, operations);
    }

    @Override
    public List<JobPo> getDeadJob(String taskTrackerNodeGroup, long deadline) {
        String tableName = JobQueueUtils.getExecutableQueueName(taskTrackerNodeGroup);
        Query<JobPo> query = template.createQuery(tableName, JobPo.class);
        query.field("isRunning").equal(false).
                order("gmtCreated");
        return query.asList();
    }
}