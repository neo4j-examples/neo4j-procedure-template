package example;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.traversal.Evaluation;
import org.neo4j.graphdb.traversal.Evaluator;
import org.neo4j.graphdb.traversal.Evaluators;
import org.neo4j.graphdb.traversal.Traverser;
import org.neo4j.logging.Log;
import org.neo4j.procedure.*;

import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Simple demo on how to use the TraversalAPI.
 * Assumes to be running on the Movie graph.
 */
public class TraverseDemo {

    static final Label PERSON = Label.label("Person");

    @Context
    public GraphDatabaseService db;

    @Context
    public Log log;

    /**
     * Uses the Traversal API to return all Person fond by a Depth of 2.
     * This could be much easier with a simple Cypher statement, but serves as a demo onl.
     * @param actorName name of the Person node to start from
     * @return Stream of Person Nodes
     */
    @Procedure(value = "travers.findCoActors", mode = Mode.READ)
    @Description("traverses starting from the Person with the given name and returns all co-actors")
    public Stream<NodeWrapper> findCoActors(@Name("actorName") String actorName) {


        Node actor = db.beginTx().findNodes(PERSON, "name", actorName)
                .stream()
                .findFirst()
                .orElseThrow(IllegalArgumentException::new);

        final Traverser traverse = db.beginTx().traversalDescription()
                .depthFirst()
                .evaluator(Evaluators.fromDepth(1))
                .evaluator(Evaluators.toDepth(2))
                .evaluator(Evaluators.includeIfAcceptedByAny(new PathLogger(), new LabelEvaluator(PERSON)))
                .traverse(actor);

        return StreamSupport
                .stream(traverse.spliterator(), false)
                .map(Path::endNode)
                .map(NodeWrapper::new);
    }


    public static class NodeWrapper {

        public final Node node;

        public NodeWrapper(Node node) {
            this.node = node;
        }
    }

    /**
     * Miss-using an evaluator to log out the path being evaluated.
     */
    private class PathLogger implements Evaluator {

        @Override
        public Evaluation evaluate(Path path) {
            log.info(path.toString());
            return Evaluation.EXCLUDE_AND_CONTINUE;
        }
    }

    private static class LabelEvaluator implements Evaluator {

        private final Label label;

        private LabelEvaluator(Label label) {
            this.label = label;
        }

        @Override
        public Evaluation evaluate(Path path) {
            if (path.endNode().hasLabel(label)) {
                return Evaluation.INCLUDE_AND_CONTINUE;
            } else {
                return Evaluation.EXCLUDE_AND_CONTINUE;
            }
        }
    }
}
