package com.cloud.ha.dao;

import com.cloud.ha.HaWork.WorkType;
import com.cloud.ha.HaWorkVO;
import com.cloud.utils.db.GenericDao;

import java.util.List;

public interface HighAvailabilityDao extends GenericDao<HaWorkVO, Long> {

    /**
     * Takes an available HA work item.
     *
     * @param serverId server that is taking this.
     * @return WorkVO if there's one to work on; null if none.
     */
    HaWorkVO take(long serverId);

    /**
     * Finds all the work items related to this instance.
     *
     * @param instanceId
     * @return list of WorkVO or empty list.
     */
    List<HaWorkVO> findPreviousHA(long instanceId);

    boolean delete(long instanceId, WorkType type);

    /**
     * Finds all the work items that were successful and is now ready to be purged.
     *
     * @param time that the work item must be successful before.
     * @return list of WorkVO or empty list.
     */
    void cleanup(long time);

    void deleteMigrationWorkItems(final long hostId, final WorkType type, final long serverId);

    List<HaWorkVO> findTakenWorkItems(WorkType type);

    /**
     * finds out if a work item has been scheduled for this work type but has not been taken yet.
     *
     * @param instanceId vm instance id
     * @param type       type of work scheduled for it.
     * @return true if it has been scheduled and false if it hasn't.
     */
    boolean hasBeenScheduled(long instanceId, WorkType type);

    int releaseWorkItems(long nodeId);

    /**
     * Look for HA work that has been scheduled for a vm since a certain work id.
     *
     * @param vmId   virtual machine id.
     * @param workId work item id.
     * @return List of work items.
     */
    List<HaWorkVO> listFutureHaWorkForVm(long vmId, long workId);

    /**
     * Look for HA work that is being run right now for a VM.
     *
     * @param vmId virtual machine id
     * @return List of work items
     */
    List<HaWorkVO> listRunningHaWorkForVm(long vmId);

    List<HaWorkVO> listPendingHaWorkForVm(long vmId);
}
