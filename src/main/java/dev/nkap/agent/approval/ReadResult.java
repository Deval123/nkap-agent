package dev.nkap.agent.approval;

import java.util.List;

/** What a read of one adjustment, or of the list, came to. */
public sealed interface ReadResult {

    record Found(AdjustmentView view) implements ReadResult {
    }

    record Listed(List<AdjustmentView> views) implements ReadResult {
        public Listed {
            views = List.copyOf(views);
        }
    }

    record Refused(String code, String text) implements ReadResult {
    }
}
