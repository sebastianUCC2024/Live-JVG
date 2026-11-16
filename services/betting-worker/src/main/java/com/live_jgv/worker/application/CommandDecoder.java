package com.live_jgv.worker.application;

import com.live_jgv.worker.domain.*;

public interface CommandDecoder { DecodedCommand decode(TransportMessage message); }
