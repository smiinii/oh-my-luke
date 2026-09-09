package io.ohmyluke.state;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class UsageEvidenceCodecTest {
    @Test void duplicateKeysAndTrailingValuesAreNotValidEvidence() {
        var state=new io.ohmyluke.graph.RunState(io.ohmyluke.graph.RunStatus.RUNNING,new io.ohmyluke.graph.NodeId("writer"),0,
                java.util.Map.of(),java.util.List.of(),java.util.List.of());
        var codec=new CheckpointCodec();
        String valid=codec.encode(RunCheckpoint.current("run","signature",CheckpointPhase.READY,state));
        assertNotNull(codec.decode(valid));
        assertThrows(CheckpointException.class,()->codec.decode(valid.replace("\"schemaVersion\" : 3","\"schemaVersion\" : 3, \"schemaVersion\" : 3")));
        assertThrows(CheckpointException.class,()->codec.decode(valid+" {}"));
        var events=new RunEventCodec();
        String event=events.encode(RunEvent.current("run",1,RunEventType.RUN_STARTED,new io.ohmyluke.graph.NodeId("writer"),
                io.ohmyluke.graph.RunStatus.RUNNING,0,"start"));
        assertNotNull(events.decode(event));
        assertThrows(CheckpointException.class,()->events.decode(event.replace("\"sequence\":1","\"sequence\":1,\"sequence\":1")));
        assertThrows(CheckpointException.class,()->events.decode(event+" {}"));
    }
}
