package edu.brown.designer.partitioners;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Map.Entry;

import org.apache.commons.collections15.map.ListOrderedMap;
import org.apache.commons.collections15.set.ListOrderedSet;
import org.apache.log4j.Logger;
import org.voltdb.catalog.CatalogType;
import org.voltdb.catalog.Column;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.ProcParameter;
import org.voltdb.catalog.Procedure;
import org.voltdb.catalog.Statement;
import org.voltdb.catalog.Table;
import org.voltdb.types.QueryType;
import org.voltdb.utils.Pair;

import edu.brown.catalog.CatalogKey;
import edu.brown.catalog.CatalogUtil;
import edu.brown.catalog.DependencyUtil;
import edu.brown.catalog.special.MultiColumn;
import edu.brown.catalog.special.MultiProcParameter;
import edu.brown.catalog.special.ReplicatedColumn;
import edu.brown.designer.AccessGraph;
import edu.brown.designer.ColumnSet;
import edu.brown.designer.DependencyGraph;
import edu.brown.designer.DesignerEdge;
import edu.brown.designer.DesignerHints;
import edu.brown.designer.DesignerInfo;
import edu.brown.designer.DesignerUtil;
import edu.brown.designer.DesignerVertex;
import edu.brown.graphs.VertexTreeWalker;
import edu.brown.mappings.ParameterMapping;
import edu.brown.mappings.ParameterMappingsSet;
import edu.brown.plannodes.PlanNodeUtil;
import edu.brown.statistics.Histogram;
import edu.brown.statistics.TableStatistics;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.LoggerUtil;
import edu.brown.utils.MathUtil;
import edu.brown.utils.LoggerUtil.LoggerBoolean;

public abstract class PartitionerUtil {
    private static final Logger LOG = Logger.getLogger(PartitionerUtil.class);
    private static final LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private static final LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }
    
    /**
     * 
     * @param <T>
     */
    private static class CatalogWeightComparator<T extends CatalogType> implements Comparator<T> {
        private final Map<T, Double> weights;
        public CatalogWeightComparator(Map<T, Double> weights) {
            this.weights = weights;
        }
        public int compare(T t0, T t1) {
            Double w0 = this.weights.get(t0);
            assert(w0 != null) : "Missing weight for " + t0;
            Double w1 = this.weights.get(t1);
            assert(w1 != null) : "Missing weight for " + t1;
            
            if (w0.equals(w1)) return (t0.getName().compareTo(t1.getName()));
            return (w1.compareTo(w0));
        };
    }

    /**
     * Returns true if a procedure should be ignored in any calculations or decision making
     * @param hints
     * @param catalog_proc
     * @return
     */
    public static boolean shouldIgnoreProcedure(final DesignerHints hints, final Procedure catalog_proc) {
        assert(catalog_proc != null);
    
        // Ignore criteria:
        //  (1) The procedure is a sysproc
        //  (2) The procedure doesn't have any input parameters (meaning it just have to be randomly assigned)
        //  (3) The procedure is set to be ignored in the given DesignerHints
        boolean ignore = PartitionerUtil.isPartitionable(catalog_proc) == false;
        if (hints != null && ignore == false) {
            ignore = hints.shouldIgnoreProcedure(catalog_proc);
        }
        return (ignore);
    }
    /**
     * Returns true if this Proce
     * @param catalog_proc
     * @return
     */
    public static boolean isPartitionable(Procedure catalog_proc) {
        assert(catalog_proc != null);
        return (!catalog_proc.getSystemproc() && catalog_proc.getParameters().size() > 0);
    }
    
    /**
     * Generate the ordered list of Procedures that we need to visit for partitioning
     * @param catalog_db
     * @param hints
     * @return
     * @throws Exception
     */
    public LinkedList<String> generateProcedureOrder(final DesignerInfo info, final Database catalog_db, final DesignerHints hints) throws Exception {
        if (debug.get()) LOG.debug("Generating Procedure visit order");
        
        final Map<Procedure, Double> proc_weights = new HashMap<Procedure, Double>();
        Histogram<String> hist = info.workload.getProcedureHistogram();
        TreeSet<Procedure> proc_visit_order = new TreeSet<Procedure>(new PartitionerUtil.CatalogWeightComparator<Procedure>(proc_weights));

        for (Procedure catalog_proc : catalog_db.getProcedures()) {
            if (catalog_proc.getSystemproc()) continue;
            String proc_key = CatalogKey.createKey(catalog_proc);
            Long weight = hist.get(proc_key);
            if (weight != null && weight > 0) {
                proc_weights.put(catalog_proc, weight.doubleValue());
                proc_visit_order.add(catalog_proc);
            }
        } // FOR
        
        // Convert to CatalogKeys
        LinkedList<String> ret = new LinkedList<String>();
        for (Procedure catalog_proc : proc_visit_order) ret.add(CatalogKey.createKey(catalog_proc));
        
        return (ret);
    }
    
    /**
     * 
     * @param catalog_db
     * @param hints
     * @return
     * @throws Exception
     */
    public static ListOrderedSet<String> generateProcParameterOrder(final DesignerInfo info, final Database catalog_db, final Procedure catalog_proc, final DesignerHints hints) throws Exception {
        // HACK: Reload the correlations file so that we can get the proper catalog objects
        ParameterMappingsSet correlations = info.getCorrelations();
        assert(correlations != null);
//        ParameterCorrelations correlations = new ParameterCorrelations();
//        assert(info.getCorrelationsFile() != null) : "The correlations file path was not set";
//        correlations.load(info.getCorrelationsFile(), catalog_db);

        // For each procedure, we need to generate a list of potential partitioning parameters
        String proc_key = CatalogKey.createKey(catalog_proc);
        assert(proc_key != null);
        
        // For each ProcParameter, get a list of the correlations that can be mapped to the partitioning columns
        // of tables. We will generate a total weight for each ProcParameter
        final Map<ProcParameter, List<Double>> param_correlations = new HashMap<ProcParameter, List<Double>>();
            
        // Get the list of tables accessed by this procedure
        for (Table catalog_tbl : CatalogUtil.getReferencedTables(catalog_proc)) {
            if (catalog_tbl.getIsreplicated()) continue;
            Column catalog_col = catalog_tbl.getPartitioncolumn();
            
            for (ProcParameter catalog_proc_param : catalog_proc.getParameters()) {
                // Skip if this is an array
                if (hints.enable_array_procparameter_candidates == false && catalog_proc_param.getIsarray()) continue;
                if (!param_correlations.containsKey(catalog_proc_param)) {
                    param_correlations.put(catalog_proc_param, new ArrayList<Double>());
                }
                // Special Case: MultiProcParameter
                if (catalog_proc_param instanceof MultiProcParameter) {
                    if (hints.enable_multi_partitioning) {
                        MultiProcParameter mpp = (MultiProcParameter)catalog_proc_param;
                        for (ProcParameter inner : mpp) {
                            // Divide the values by the number of attributes in mpp so that we take the average
                            for (ParameterMapping c : correlations.get(inner, catalog_col)) {
                                param_correlations.get(catalog_proc_param).add(c.getCoefficient() / (double)mpp.size());
                            } // FOR (Correlation)
                        } // FOR
                    }
                } else {
                    for (ParameterMapping c : correlations.get(catalog_proc_param, catalog_col)) {
                        param_correlations.get(catalog_proc_param).add(c.getCoefficient());
                    } // FOR (Correlation)
                }
            } // FOR (ProcParameter)
        } // FOR (Table)
            
        // The weights for each ProcParameter will be the geometric mean of the correlation coefficients
        Map<ProcParameter, Double> param_weights = new HashMap<ProcParameter, Double>();
        for (Entry<ProcParameter, List<Double>> e : param_correlations.entrySet()) {
            List<Double> weights_list = e.getValue();
            if (!weights_list.isEmpty()) {
                double weights[] = new double[weights_list.size()];
                for (int i = 0; i < weights.length; i++) 
                    weights[i] = weights_list.get(i);
                double mean = MathUtil.geometricMean(weights, MathUtil.GEOMETRIC_MEAN_ZERO);
                param_weights.put(e.getKey(), mean);
            }
        } // FOR

        // Convert to CatalogKeys
        ListOrderedSet<String> ret = new ListOrderedSet<String>();
        
        // If there were no matches, then we'll just include all of the attributes
        if (param_weights.isEmpty()) {
            if (debug.get()) LOG.warn("No parameter correlations found for " + catalog_proc.getName() + ". Returning all candidates!");
            for (ProcParameter catalog_proc_param : catalog_proc.getParameters()) {
                if (hints.enable_multi_partitioning || !(catalog_proc_param instanceof MultiProcParameter)) {
                    ret.add(CatalogKey.createKey(catalog_proc_param));    
                }
            } // FOR
        } else {
            List<ProcParameter> param_visit_order = new ArrayList<ProcParameter>(param_weights.keySet());
            Collections.sort(param_visit_order, new PartitionerUtil.CatalogWeightComparator<ProcParameter>(param_weights));
            for (ProcParameter catalog_proc_param : param_visit_order) ret.add(CatalogKey.createKey(catalog_proc_param));
        } // FOR
        return (ret);
    }
    
    public static class VerticalPartitionCandidate extends Pair<Set<Column>, Set<Statement>> {
        public VerticalPartitionCandidate(Set<Column> cols) {
            super(cols, new TreeSet<Statement>(), false);
        }
        public Set<Column> getColumns() {
            return (this.getFirst());
        }
        public Set<Statement> getStatements() {
            return (this.getSecond());
        }
    }
    
    /**
     * 
     * @param info
     * @param agraph
     * @param catalog_tbl
     * @param hints
     * @return
     * @throws Exception
     */
    public static Collection<VerticalPartitionCandidate> generateVerticalPartitioningCandidates(final DesignerInfo info, final AccessGraph agraph, final Column catalog_col, final DesignerHints hints) throws Exception {
        final Table catalog_tbl = catalog_col.getParent();
        Map<Set<Column>, VerticalPartitionCandidate> candidates = new HashMap<Set<Column>, VerticalPartitionCandidate>();
        
        // For the given Column object, figure out what are the potential vertical partitioning candidates
        // if we assume that the Table is partitioned on that Column
        for (Procedure catalog_proc : CatalogUtil.getReferencingProcedures(catalog_tbl)) {
            
            // Look for a query on this table that does not use the target column in the predicate
            // But does return it in its output
            for (Statement catalog_stmt : catalog_proc.getStatements()) {
                // We can only look at SELECT statements because we have know way to know the correspondence
                // between the candidate partitioning column and our target column
                if (catalog_stmt.getQuerytype() != QueryType.SELECT.getValue()) continue;
                Set<Column> target_cols = PlanNodeUtil.getOutputColumnsForStatement(catalog_stmt);
                if (target_cols.contains(catalog_col) == false) continue;
                
                // The referenced columns are the columns that are used in the predicate
                // We always want to remove the reference to our target Column
                Set<Column> ref_cols = new TreeSet<Column>(CatalogUtil.getReferencedColumns(catalog_stmt));
                ref_cols.remove(catalog_col);
                if (ref_cols.isEmpty()) continue;
                
                // Check whether we already have a candidate with these columns
                VerticalPartitionCandidate vpc = candidates.get(ref_cols);
                if (vpc == null) {
                    vpc = new VerticalPartitionCandidate(ref_cols);
                    candidates.put(ref_cols, vpc);
                }
                vpc.getStatements().add(catalog_stmt);
                LOG.info(String.format("%s: Output%s All%s\nCandidate: %s\n", catalog_stmt.fullName(), target_cols, ref_cols, vpc));
            } // FOR (stmt)
            
        } // FOR (proc)
        
        return (candidates.values());
        
    }
    /**
     * Generate an ordered list of the tables that define how we should traverse the search tree
     * @param agraph
     * @param hints
     * @return
     * @throws Exception
     */
    public static List<String> generateTableOrder(final DesignerInfo info, final AccessGraph agraph, final DesignerHints hints) throws Exception {
        final LinkedList<String> table_visit_order = new LinkedList<String>();

        // Put small read-only tables at the top of the list so that we can try everything with
        // replicating them first
        if (hints.force_replication_size_limit != null) {
            final Map<Table, Double> replication_weights = new HashMap<Table, Double>();
            final TreeSet<Table> temp_list = new TreeSet<Table>(new PartitionerUtil.CatalogWeightComparator<Table>(replication_weights));
            for (Table catalog_tbl : info.catalog_db.getTables()) {
                TableStatistics ts = info.stats.getTableStatistics(catalog_tbl);
                assert(ts != null);
                double size_ratio = ts.tuple_size_total / (double)hints.max_memory_per_partition;
                if (ts.readonly && size_ratio <= hints.force_replication_size_limit) {
                    if (debug.get()) LOG.debug(CatalogUtil.getDisplayName(catalog_tbl) + " is read-only and only " + String.format("%.02f", (size_ratio * 100)) + "% of total memory. Forcing replication...");
                    replication_weights.put(catalog_tbl, size_ratio);
                    temp_list.add(catalog_tbl);
                }
            } // FOR
            for (Table catalog_tbl : temp_list) {
                table_visit_order.addLast(CatalogKey.createKey(catalog_tbl));
            }
            Collections.reverse(table_visit_order);
            if (debug.get()) LOG.debug("Forced Replication: " + table_visit_order);
        }
        
        for (DesignerVertex root : DesignerUtil.createCandidateRoots(info, hints, agraph)) {
            if (debug.get()) LOG.debug("Examining edges for candidate root '" + root.getCatalogItem().getName() + "'");
            // From each candidate root, traverse the graph in breadth first order based on
            // the edge weights in the AccessGraph
            new VertexTreeWalker<DesignerVertex, DesignerEdge>(info.dgraph, VertexTreeWalker.TraverseOrder.BREADTH) {
                @Override
                protected void populate_children(VertexTreeWalker.Children<DesignerVertex> children, DesignerVertex element) {
                    // For the current element, look at all of its children and count up the total
                    // weight of all the edges to each child
                    final Map<Table, Double> vertex_weights = new HashMap<Table, Double>();
                    DependencyGraph dgraph = (DependencyGraph)this.getGraph();
                    
                    if (agraph.containsVertex(element)) {
                        for (DesignerVertex child : dgraph.getSuccessors(element)) {
                            Table child_tbl = child.getCatalogItem();
                            DesignerVertex child_vertex = this.getGraph().getVertex(child_tbl);
                            
                            if (agraph.containsVertex(child_vertex)) {
                                for (DesignerEdge edge : agraph.findEdgeSet(element, child_vertex)) {
                                    Double orig_weight = vertex_weights.get(child_tbl);
                                    if (orig_weight == null) orig_weight = 0.0d;
                                    vertex_weights.put(child_tbl, orig_weight + edge.getTotalWeight());
                                } // FOR
                            }
                        } // FOR
                    }
                    
                    // Now sort the children them by weights and throw them at the walker
                    List<Table> sorted = new ArrayList<Table>(vertex_weights.keySet());
                    Collections.sort(sorted, new PartitionerUtil.CatalogWeightComparator<Table>(vertex_weights));
                    for (Table child_tbl : sorted) {
                        children.addAfter(this.getGraph().getVertex(child_tbl));
                    } // FOR
                    if (debug.get()) { 
                        LOG.debug(element);
                        LOG.debug("  sorted=" + sorted);
                        LOG.debug("  weights=" + vertex_weights);
                        LOG.debug("  children=" + children);
                    }
                };
                
                @Override
                protected void callback(DesignerVertex element) {
                    Table catalog_tbl = element.getCatalogItem();
                    String table_key = CatalogKey.createKey(catalog_tbl);
                    if (!table_visit_order.contains(table_key)) {
                        table_visit_order.addLast(table_key);
                    }
                }
            }.traverse(root);
        } // FOR
        
        // Add in any missing tables to the end of the list
        // This can occur if there are tables that do not appear in the AccessGraph for whatever reason
        // Note that we have to traverse the graph so that we don't try to plan a parent before a child
//        for (DesignerVertex root : info.dgraph.getRoots()) {
//            if (trace.get()) LOG.trace("Creating table visit order starting from root " + root);
//            
//            new VertexTreeWalker<DesignerVertex, DesignerEdge>(info.dgraph, VertexTreeWalker.TraverseOrder.BREADTH) {
//                protected void callback(DesignerVertex element) {
//                    Table catalog_tbl = element.getCatalogItem();
//                    assert(catalog_tbl != null);
//                    String table_key = CatalogKey.createKey(catalog_tbl);
//                    if (!table_visit_order.contains(table_key)) {
//                        if (debug.get()) LOG.warn("Added " + catalog_tbl + " because it does not appear in the AccessGraph");
//                        table_visit_order.add(table_key);
//                    }
//                };
//            }.traverse(root);
//            if (table_visit_order.size() == info.catalog_db.getTables().size()) break;
//        } // FOR
        return (table_visit_order);
    }
    
    /**
     * For a given table, generate the search order of its columns
     * @param agraph
     * @param catalog_tbl
     * @param hints
     * @return
     * @throws Exception
     */
    public static LinkedList<String> generateColumnOrder(final DesignerInfo info, final AccessGraph agraph, final Table catalog_tbl, final DesignerHints hints) throws Exception {
        return (PartitionerUtil.generateColumnOrder(info, agraph, catalog_tbl, hints, false, false));
    }
    
    /**
     * 
     * @param info
     * @param agraph
     * @param catalog_tbl
     * @param hints
     * @param no_replication
     * @param force_replication_last
     * @return
     * @throws Exception
     */
    public static LinkedList<String> generateColumnOrder(final DesignerInfo info, final AccessGraph agraph, final Table catalog_tbl, final DesignerHints hints, boolean no_replication, boolean force_replication_last) throws Exception {
        assert(agraph != null);
        final LinkedList<String> ret = new LinkedList<String>();
        final String table_key = CatalogKey.createKey(catalog_tbl);
        
        // Force columns in hints
        Set<Column> force_columns = hints.getTablePartitionCandidates(catalog_tbl);
        if (!force_columns.isEmpty()) {
            if (debug.get()) LOG.debug("Force " + catalog_tbl + " candidates: " + force_columns);
            for (Column catalog_col : force_columns) {
                ret.add(CatalogKey.createKey(catalog_col));
            }
            return (ret);
        }
        
        // Get a list of this table's attributes that were used in the workload. Luckily, the AccessGraph
        // already has that for us. We'll sort them by their weights so that we traverse the likely
        // best candidate first, thereby pruning other branches more quickly
        final Map<Column, Double> column_weights = new HashMap<Column, Double>(); 
        // SortedMap<Double, Collection<Column>> weighted_columnsets = new TreeMap<Double, Collection<Column>>(Collections.reverseOrder());
        DesignerVertex vertex = agraph.getVertex(catalog_tbl);
        assert(vertex != null) : "No vertex exists in AccesGraph for " + catalog_tbl;
        if (debug.get()) LOG.debug("Retreiving edges for " + vertex + " from AccessGraph");
        Collection<DesignerEdge> edges = agraph.getIncidentEdges(vertex);
        if (edges == null) {
            if (debug.get()) LOG.warn("No edges were found for " + vertex + " in AccessGraph");
        } else {
            for (DesignerEdge edge : agraph.getIncidentEdges(vertex)) {
                ColumnSet orig_cset = (ColumnSet)edge.getAttribute(AccessGraph.EdgeAttributes.COLUMNSET.name());
                assert(orig_cset != null);
    
                // Skip any ColumnSets that were used only for INSERTs
                ColumnSet cset = new ColumnSet();
                for (ColumnSet.Entry entry : orig_cset) {
                    if (!(entry.getQueryTypes().contains(QueryType.INSERT) && entry.getQueryTypes().size() == 1)) {
                        cset.add(entry);
                    }
                } // FOR
                
                double edge_weight = edge.getTotalWeight();
                for (Column catalog_col : cset.findAllForParent(Column.class, catalog_tbl)) {
                    Double column_weight = column_weights.get(catalog_col);
                    if (column_weight == null) column_weight = 0.0d;
                    column_weights.put(catalog_col, column_weight + edge_weight);
                } // FOR            
            } // FOR
        }
        
        // Increase the weight of the columns based on the number foreign key descendants they have
        DependencyUtil dependencies = DependencyUtil.singleton(CatalogUtil.getDatabase(catalog_tbl));
        if (debug.get()) LOG.debug("Calculating descendants for columns");
        for ( Entry<Column, Double> entry : column_weights.entrySet()) {
        	Column catalog_col = entry.getKey();
            Double weight = entry.getValue();
            int descendants = dependencies.getDescendants(catalog_col).size();
            column_weights.put(catalog_col, weight * (descendants + 1));
            if (descendants > 0) LOG.debug("  " + catalog_col + ": " + descendants);
        } // FOR
        
        // Now sort them by the weights
        // TODO: Do we want to consider ancestory paths? Like what if there is a foreign key that is
        // used down in a bunch of children tables? Well, then if they are really important relationships,
        // they will show up with greater weights in the 
        LinkedList<Column> sorted = new LinkedList<Column>(column_weights.keySet());
        Collections.sort(sorted, new PartitionerUtil.CatalogWeightComparator<Column>(column_weights));
        
        if (debug.get()) {
            LOG.debug(catalog_tbl);
            LOG.debug("  sorted=" + sorted);
            LOG.debug("  weights=" + column_weights);
            LOG.debug("  children=" + agraph.getIncidentEdges(vertex));
        }
    
        // Always add replicated column placeholder
        // Simple Optimization: Put the replication placeholder as the last attribute in the 
        // list if the table is not read-only
        if (!no_replication) {
            ReplicatedColumn replicated_col = ReplicatedColumn.get(catalog_tbl);
            if (info.stats.getTableStatistics(table_key).readonly && !force_replication_last) {
                sorted.add(0, replicated_col);
            } else {
                sorted.add(replicated_col);
            }
        }
    
        // Convert to CatalogKeys
        for (Column catalog_col : sorted) ret.add(CatalogKey.createKey(catalog_col));
        return (ret);
    }
    
    /**
     * 
     * @param info
     * @param hints
     * @param catalog_proc
     * @return
     * @throws Exception
     */
    public static Histogram<Column> generateProcedureColumnAccessHistogram(final DesignerInfo info, final DesignerHints hints, final AccessGraph agraph, final Procedure catalog_proc) throws Exception {
        LOG.debug("Constructing column access histogram for " + catalog_proc.getName());
        Histogram<Column> column_histogram = new Histogram<Column>();
        for (Table catalog_tbl : CatalogUtil.getReferencedTables(catalog_proc)) {
            DesignerVertex v = agraph.getVertex(catalog_tbl);
            for (DesignerEdge e : agraph.getIncidentEdges(v)) {
                Collection<DesignerVertex> vertices = agraph.getVertices();
                if (vertices.size() != 1) {
                    DesignerVertex v0 = CollectionUtil.get(vertices, 0);
                    DesignerVertex v1 = CollectionUtil.get(vertices, 1);
                    if (v0.equals(v) && v1.equals(v)) continue;
                }
                
                double edge_weight = e.getTotalWeight();
                ColumnSet cset = e.getAttribute(AccessGraph.EdgeAttributes.COLUMNSET.name());
                Histogram<Column> cset_histogram = cset.buildHistogramForType(Column.class);
                Set<Column> columns = cset_histogram.values();
                for (Column catalog_col : columns) {
                    if (!catalog_col.getParent().equals(catalog_tbl)) continue;
                    long cnt = cset_histogram.get(catalog_col);
                    column_histogram.put(catalog_col, Math.round(cnt * edge_weight));    
                } // FOR
            } // FOR (EDGE)
        } // FOR (TABLE)
        return (column_histogram);
    }
    /**
     * Generate a cross-product of 
     * @param info
     * @param hints
     * @param catalog_proc
     */
    public static Map<ProcParameter, Set<MultiProcParameter>> generateMultiProcParameters(final DesignerInfo info, final DesignerHints hints, final Procedure catalog_proc) {
        List<ProcParameter> params = new ArrayList<ProcParameter>();
        Map<ProcParameter, Set<MultiProcParameter>> param_map = new ListOrderedMap<ProcParameter, Set<MultiProcParameter>>();
        CollectionUtil.addAll(params, catalog_proc.getParameters());
        
        // Why do I need to make a map like this?
        for (ProcParameter catalog_param : params) {
            param_map.put(catalog_param, new HashSet<MultiProcParameter>());
        } // FOR
    
        for (int i = 0, cnt = params.size(); i < cnt; i++) {
            ProcParameter param0 = params.get(i);
            assert(param0 != null);
            if (param0 instanceof MultiProcParameter || param0.getIsarray()) continue;
            
            for (int ii = i + 1; ii < cnt; ii++) {
                ProcParameter param1 = params.get(ii);
                assert(param1 != null);
                if (param1 instanceof MultiProcParameter || param1.getIsarray()) continue;
                
                // This will automatically update the Procedure, so there isn't anything more 
                // we need to do here...
                MultiProcParameter mpp = MultiProcParameter.get(param0, param1);
                assert(mpp != null);
                param_map.get(param0).add(mpp);
                param_map.get(param1).add(mpp);
            } // FOR
        } // FOR
        return (param_map);
    }
    /**
     * 
     * @param info
     * @param hints
     * @param catalog_proc
     * @param agraph
     * @return
     */
    protected static Map<Table, Set<MultiColumn>> generateMultiColumns(final DesignerInfo info, final DesignerHints hints, final Procedure catalog_proc) {
        Map<Table, Set<MultiColumn>> multicolumns = new HashMap<Table, Set<MultiColumn>>();
    
       // For each Statement, find the columns that are accessed together
       for (Statement catalog_stmt : catalog_proc.getStatements()) {
           // Skip inserts for now...
           if (catalog_stmt.getQuerytype() == QueryType.INSERT.getValue()) continue;
           
           Set<Column> columns = CatalogUtil.getReferencedColumns(catalog_stmt);
           // For now we only bother with two-column pairs
           for (Column catalog_col0 : columns) {
               Table catalog_tbl = catalog_col0.getParent();
               if (!multicolumns.containsKey(catalog_tbl)) {
                   multicolumns.put(catalog_tbl, new HashSet<MultiColumn>());
               }
               for (Column catalog_col1 : columns) {
                   if (catalog_col0.equals(catalog_col1) || !catalog_tbl.equals(catalog_col1.getParent())) continue;
                   MultiColumn mc = MultiColumn.get(catalog_col0, catalog_col1);
                   assert(mc != null);
                   multicolumns.get(catalog_tbl).add(mc);
               } // FOR
           } // FOR
       } // FOR
       return (multicolumns);
    }

}
