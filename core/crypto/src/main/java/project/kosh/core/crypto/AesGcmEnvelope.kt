package project.kosh.core.crypto

class AesGcmEnvelope {
    // wait why this?? were not we going for AES GCM?? i know even though its very small payload...
    // but again... why new method here?
    // also why do you think is this algorithm a better choice here...
    // one more thing, dont just agree with me, i want constructive criticism
    // + why am i saying this... because of two constraints, because later we gonna make clients for other platforms as well
    // that means, compatiblity and performance chew due to encryption is bringing its own set of pain points
}