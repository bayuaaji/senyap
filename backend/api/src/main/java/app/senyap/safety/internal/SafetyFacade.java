package app.senyap.safety.internal;

import app.senyap.safety.ImageScreening;
import app.senyap.safety.ImageVerdict;
import app.senyap.safety.SafetyApi;
import app.senyap.safety.TextScreening;
import app.senyap.safety.TextVerdict;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Implementasi {@link SafetyApi}. */
@Service
class SafetyFacade implements SafetyApi {

    private final BlockService blocks;
    private final ScreeningService screening;

    SafetyFacade(BlockService blocks, ScreeningService screening) {
        this.blocks = blocks;
        this.screening = screening;
    }

    @Override
    public Set<Long> blockedEitherWay(long userId, List<Long> others) {
        return blocks.blockedEitherWay(userId, others);
    }

    @Override
    public void block(long blockerId, long blockedId, Long conversationId) {
        blocks.block(blockerId, blockedId, conversationId);
    }

    @Override
    public TextVerdict screenText(TextScreening request) {
        return screening.screenText(request);
    }

    @Override
    public ImageVerdict screenImage(ImageScreening request) {
        return screening.screenImage(request);
    }
}
