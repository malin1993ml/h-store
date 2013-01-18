package org.voltdb.sysprocs;

import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.voltdb.DependencySet;
import org.voltdb.ParameterSet;
import org.voltdb.ProcInfo;
import org.voltdb.VoltSystemProcedure;
import org.voltdb.VoltTable;
import org.voltdb.VoltTable.ColumnInfo;
import org.voltdb.VoltType;
import org.voltdb.catalog.Table;
import org.voltdb.jni.ExecutionEngine;
import org.voltdb.types.TimestampType;
import org.voltdb.utils.Pair;

import edu.brown.hstore.PartitionExecutor;
import edu.brown.profilers.AntiCacheManagerProfiler;

/** 
 * 
 */
@ProcInfo(
    partitionParam = 0,
    singlePartition = true
)
public class EvictTuples extends VoltSystemProcedure {
    @SuppressWarnings("unused")
    private static final Logger LOG = Logger.getLogger(EvictTuples.class);

    public static final ColumnInfo ResultsColumns[] = {
        new ColumnInfo(VoltSystemProcedure.CNAME_HOST_ID, VoltSystemProcedure.CTYPE_ID),
        new ColumnInfo("HOSTNAME", VoltType.STRING),
        new ColumnInfo("PARTITION", VoltType.INTEGER),
        new ColumnInfo("TABLE", VoltType.STRING),
        new ColumnInfo("TUPLES_EVICTED", VoltType.INTEGER),
        new ColumnInfo("BLOCKS_EVICTED", VoltType.INTEGER),
        new ColumnInfo("BYTES_EVICTED", VoltType.BIGINT),
        new ColumnInfo("CREATED", VoltType.TIMESTAMP),
    };
    
    @Override
    public void initImpl() {
        executor.registerPlanFragment(SysProcFragmentId.PF_antiCacheEviction, this);
    }

    @Override
    public DependencySet executePlanFragment(Long txn_id,
                                             Map<Integer, List<VoltTable>> dependencies,
                                             int fragmentId,
                                             ParameterSet params,
                                             PartitionExecutor.SystemProcedureExecutionContext context) {
        assert(fragmentId == SysProcFragmentId.PF_antiCacheEviction);
        throw new IllegalAccessError("Invalid invocation of " + this.getClass() + ".executePlanFragment()");
    }
    
    public VoltTable[] run(int partition, String tableNames[], long blockSizes[]) {
        ExecutionEngine ee = executor.getExecutionEngine();
        assert(tableNames.length == blockSizes.length);
        
        // PROFILER
        AntiCacheManagerProfiler profiler = null;
        long start = -1;
        if (hstore_conf.site.anticache_profiling) {
            start = System.currentTimeMillis();
            profiler = hstore_site.getAntiCacheManager().getDebugContext().getProfiler(this.partitionId);
            profiler.eviction_time.start();
        }

        // Check Input
        if (tableNames.length == 0) {
            throw new VoltAbortException("No tables to evict were given");
        }
        Table tables[] = new Table[tableNames.length];
        for (int i = 0; i < tableNames.length; i++) {
            tables[i] = catalogContext.database.getTables().getIgnoreCase(tableNames[i]);
            if (tables[i] == null) {
                String msg = String.format("Unknown table '%s'", tableNames[i]);
                throw new VoltAbortException(msg);
            }
            else if (tables[i].getEvictable() == false) {
                String msg = String.format("Trying to evict tuples from table '%s' but it is not marked as evictable", tables[i].getName());
                throw new VoltAbortException(msg);
            }
            else if (blockSizes[i] <= 0) {
                String msg = String.format("Invalid block eviction size '%d' for table '%s'", blockSizes[i], tables[i].getName());
                throw new VoltAbortException(msg);
            }
        } // FOR
        
        // TODO: Instead of sending down requests one at a time per table, it will
        //       be much faster if we just send down the entire batch
        final VoltTable allResults = new VoltTable(ResultsColumns);
        for (int i = 0; i < tableNames.length; i++) {
            VoltTable vt = ee.antiCacheEvictBlock(tables[i], blockSizes[i]);
            boolean adv = vt.advanceRow();
            assert(adv);
            Object row[] = {
                    this.hstore_site.getSiteId(),
                    this.hstore_site.getSiteName(),
                    this.executor.getPartitionId(),
                    vt.getString("TABLE_NAME"),
                    vt.getLong("TUPLES_EVICTED"),
                    vt.getLong("BLOCKS_EVICTED"),
                    vt.getLong("BYTES_EVICTED"),
                    new TimestampType()
            };
            allResults.addRow(row);
        } // FOR
        
        // PROFILER
        if (profiler != null) {
            profiler.eviction_timestamps.add(Pair.of(start, System.currentTimeMillis()));
            profiler.eviction_time.stopIfStarted();
        }
        
        return new VoltTable[]{ allResults };
    }
}
