package com.live_jgv.betting.application;

import com.live_jgv.betting.domain.*;

public interface EventDecoder {
    DecodedInput decode(InputRecord record);
}
