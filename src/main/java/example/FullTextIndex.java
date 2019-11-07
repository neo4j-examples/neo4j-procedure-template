package example;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.index.IndexManager;
import org.neo4j.logging.Log;
import org.neo4j.procedure.*;

import static org.neo4j.helpers.collection.MapUtil.stringMap;

/**
 * This is an example showing how you could expose Neo4j's full text indexes as
 * two procedures - one for updating indexes, and one for querying by label and
 * the lucene query language.
 */
public class FullTextIndex {

    // Only static fields and @Context-annotated fields are allowed in
    // Procedure classes. This static field is the configuration we use
    // to create full-text indexes.
    private static final Map<String, String> FULL_TEXT =
            stringMap(IndexManager.PROVIDER, "lucene", "type", "fulltext");

    // This field declares that we need a GraphDatabaseService
    // as context when any procedure in this class is invoked
    @Context
    public GraphDatabaseService db;

    // This gives us a log instance that outputs messages to the
    // standard log, normally found under `data/log/console.log`
    @Context
    public Log log;

    /**
     * This declares the first of two procedures in this class - a
     * procedure that performs queries in a legacy index.
     * <p>
     * It returns a Stream of Records, where records are
     * specified per procedure. This particular procedure returns
     * a stream of {@link SearchHit} records.
     * <p>
     * The arguments to this procedure are annotated with the
     * {@link Name} annotation and define the position, name
     * and type of arguments required to invoke this procedure.
     * There is a limited set of types you can use for arguments,
     * these are as follows:
     *
     * <ul>
     *     <li>{@link String}</li>
     *     <li>{@link Long} or {@code long}</li>
     *     <li>{@link Double} or {@code double}</li>
     *     <li>{@link Number}</li>
     *     <li>{@link Boolean} or {@code boolean}</li>
     *     <li>{@link java.util.Map} with key {@link String} and value {@link Object}</li>
     *     <li>{@link java.util.List} of elements of any valid argument type, including {@link java.util.List}</li>
     *     <li>{@link Object}, meaning any of the valid argument types</li>
     * </ul>
     *
     * @param label the label name to query by
     * @param query the lucene query, for instance `name:Brook*` to
     *              search by property `name` and find any value starting
     *              with `Brook`. Please refer to the Lucene Query Parser
     *              documentation for full available syntax.
     * @return the nodes found by the query
     */
    @Procedure(value = "example.search")
    @Description("Execute lucene query in the given index, return found nodes")
    public Stream<SearchHit> search(@Name("label") String label,
                                    @Name("query") String query) {
        String index = indexName(label);

        // Avoid creating the index, if it's not there we won't be
        // finding anything anyway!
        if (!db.index().existsForNodes(index)) {
            // Just to show how you'd do logging
            log.debug("Skipping index query since index does not exist: `%s`", index);
            return Stream.empty();
        }

        // If there is an index, do a lookup and convert the result
        // to our output record.
        return db.index()
                .forNodes(index)
                .query(query)
                .stream()
                .map(SearchHit::new);
    }

    /**
     * This is the second procedure defined in this class, it is used to update the
     * index with nodes that should be queryable. You can send the same node multiple
     * times, if it already exists in the index the index will be updated to match
     * the current state of the node.
     * <p>
     * This procedure works largely the same as {@link #search(String, String)},
     * with two notable differences. One, it is annotated with {@link Mode}.WRITE,
     * which is <i>required</i> if you want to perform updates to the graph in your
     * procedure.
     * <p>
     * Two, it returns {@code void} rather than a stream. This is simply a short-hand
     * for saying our procedure always returns an empty stream of empty records.
     *
     * @param nodeId   the id of the node to index
     * @param propKeys a list of property keys to index, only the ones the node
     *                 actually contains will be added
     */
    @Procedure(value = "example.index", mode = Mode.WRITE)
    @Description("For the node with the given node-id, add properties for the provided keys to index per label")
    public void index(@Name("nodeId") long nodeId,
                      @Name("properties") List<String> propKeys) {
        Node node = db.getNodeById(nodeId);

        // Load all properties for the node once and in bulk,
        // the resulting set will only contain those properties in `propKeys`
        // that the node actually contains.
        Set<Map.Entry<String, Object>> properties =
                node.getProperties(propKeys.toArray(new String[0])).entrySet();

        // Index every label (this is just as an example, we could filter which labels to index)
        for (Label label : node.getLabels()) {
            Index<Node> index = db.index().forNodes(indexName(label.name()), FULL_TEXT);

            // In case the node is indexed before, remove all occurrences of it so
            // we don't get old or duplicated data
            index.remove(node);

            // And then index all the properties
            for (Map.Entry<String, Object> property : properties) {
                index.add(node, property.getKey(), property.getValue());
            }
        }
    }


    /**
     * This is the output record for our search procedure. All procedures
     * that return results return them as a Stream of Records, where the
     * records are defined like this one - customized to fit what the procedure
     * is returning.
     * <p>
     * These classes can only have public non-final fields, and the fields must
     * be one of the following types:
     *
     * <ul>
     *     <li>{@link String}</li>
     *     <li>{@link Long} or {@code long}</li>
     *     <li>{@link Double} or {@code double}</li>
     *     <li>{@link Number}</li>
     *     <li>{@link Boolean} or {@code boolean}</li>
     *     <li>{@link org.neo4j.graphdb.Node}</li>
     *     <li>{@link org.neo4j.graphdb.Relationship}</li>
     *     <li>{@link org.neo4j.graphdb.Path}</li>
     *     <li>{@link java.util.Map} with key {@link String} and value {@link Object}</li>
     *     <li>{@link java.util.List} of elements of any valid field type, including {@link java.util.List}</li>
     *     <li>{@link Object}, meaning any of the valid field types</li>
     * </ul>
     */
    public static class SearchHit {
        // This records contain a single field named 'nodeId'
        public long nodeId;

        public SearchHit(Node node) {
            this.nodeId = node.getId();
        }
    }

    private String indexName(String label) {
        return "label-" + label;
    }
}
