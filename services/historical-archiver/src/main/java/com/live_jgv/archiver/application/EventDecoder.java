package com.live_jgv.archiver.application;

import com.live_jgv.archiver.domain.*;

public interface EventDecoder {
    DecodedInput decode(InputRecord record);
}
