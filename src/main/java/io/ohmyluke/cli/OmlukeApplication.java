package io.ohmyluke.cli;

import io.ohmyluke.graph.GraphRunner;
import io.ohmyluke.graph.GraphValidator;
import io.ohmyluke.runtime.ManagedRunService;
import io.ohmyluke.state.CheckpointCodec;
import io.ohmyluke.state.CheckpointStore;
import io.ohmyluke.state.EventLogStore;
import io.ohmyluke.state.HandoffStore;
import io.ohmyluke.state.RunEventCodec;
import java.nio.file.Path;

/** Entry point for the Oh My Luke command-line application. */
public final class OmlukeApplication {
    private static final String PRODUCT_NAME = "Oh My Luke";

    private OmlukeApplication() {
    }

    public static void main(String[] args) {
        ManagedRunService runs = new ManagedRunService(
                new GraphRunner(new GraphValidator()),
                new CheckpointStore(Path.of(""), new CheckpointCodec()),
                new EventLogStore(Path.of(""), new RunEventCodec()),
                new HandoffStore(Path.of("")));
        int exitCode = new OmlukeCli(runs, GraphResolver.none(), System.out, System.err)
                .execute(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static String productName() {
        return PRODUCT_NAME;
    }
}
