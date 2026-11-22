package com.deepstudent.platform.network

import com.deepstudent.platform.network.internal.OkHttpTransportEngine

/** Owns its client, active calls and idle connection pool. Close when its process owner ends. */
class OkHttpNetworkTransport(log: NetworkLog = NetworkLog.NONE) : NetworkTransport by OkHttpTransportEngine(log)
