/*
 * Copyright 2012-2023 Aerospike, Inc.
 *
 * Portions may be licensed to Aerospike, Inc. under one or more contributor
 * license agreements WHICH ARE COMPATIBLE WITH THE APACHE LICENSE, VERSION 2.0.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.aerospike.client.async;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

import com.aerospike.client.AerospikeException;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoEventLoopGroup;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.kqueue.KQueueSocketChannel;
import io.netty.channel.local.LocalIoHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.uring.IoUringIoHandler;
import io.netty.channel.uring.IoUringSocketChannel;
import io.netty.util.concurrent.EventExecutor;

/**
 * Aerospike wrapper around netty event loops.
 * Implements the Aerospike EventLoops interface.
 */
public final class NettyEventLoops implements EventLoops {

	private final Map<EventExecutor,NettyEventLoop> eventLoopMap;
	private final NettyEventLoop[] eventLoopArray;
	private final EventLoopGroup group;
	final EventLoopType eventLoopType;
	private int eventIter;

	/**
	 * Create Aerospike event loop wrappers from given netty event loops.
	 */
	public NettyEventLoops(EventLoopGroup group) {
		this(new EventPolicy(), group);
	}

	/**
	 * Create Aerospike event loop wrappers from given netty event loops.
	 * The type of event loop is determined from the event loop group instance.
	 */
	public NettyEventLoops(EventPolicy policy, EventLoopGroup group) {
		this(policy, group, getEventLoopType(group));
	}

	/**
	 * Create Aerospike event loop wrappers from given netty event loops and specified event loop type.
	 */
	public NettyEventLoops(EventPolicy policy, EventLoopGroup group, EventLoopType type) {
		if (policy.minTimeout < 5) {
			throw new AerospikeException("Invalid minTimeout " + policy.minTimeout + ". Must be at least 5ms.");
		}
		this.group = group;
		this.eventLoopType = type;

		ArrayList<NettyEventLoop> list = new ArrayList<NettyEventLoop>();
		Iterator<EventExecutor> iter = group.iterator();
		int count = 0;

		while (iter.hasNext()) {
			EventExecutor eventExecutor = iter.next();
			list.add(new NettyEventLoop(policy, (io.netty.channel.EventLoop)eventExecutor, this, count++));
		}

		eventLoopArray = list.toArray(new NettyEventLoop[count]);
		eventLoopMap = new IdentityHashMap<EventExecutor,NettyEventLoop>(count);

		for (NettyEventLoop eventLoop : eventLoopArray) {
			eventLoopMap.put(eventLoop.eventLoop, eventLoop);
		}

		// Start timer in each event loop thread.
		for (NettyEventLoop eventLoop : eventLoopArray) {
			eventLoop.execute(new Runnable() {
				public void run() {
					eventLoop.timer.start();
				}
			});
		}
	}

	private static EventLoopType getEventLoopType(EventLoopGroup group) {
		try {
			if (group instanceof IoEventLoopGroup g) {
				if (g.isIoType(EpollIoHandler.class)) {
					return EventLoopType.NETTY_EPOLL;
				} else if (g.isIoType(KQueueIoHandler.class)) {
					return EventLoopType.NETTY_KQUEUE;
				} else if (g.isIoType(IoUringIoHandler.class)) {
					return EventLoopType.NETTY_IOURING;
				} else if (g.isIoType(NioIoHandler.class)) {
					return EventLoopType.NETTY_NIO;
				} else if (g.isIoType(LocalIoHandler.class)) {
					return EventLoopType.DIRECT_NIO;
				}
			}
		}catch (NoClassDefFoundError e) {
		}

		throw new AerospikeException("Unexpected EventLoopGroup");
	}

	/**
	 * Return SocketChannel class to use in NettyChannelBuilder.
	 */
	public Class<? extends SocketChannel> getSocketChannelClass() {
		switch (eventLoopType) {
		default:
		case NETTY_NIO:
			return NioSocketChannel.class;

		case NETTY_EPOLL:
			return EpollSocketChannel.class;

		case NETTY_KQUEUE:
			return KQueueSocketChannel.class;

		case NETTY_IOURING:
			return IoUringSocketChannel.class;
		}
	}

	/**
	 * Return corresponding Aerospike event loop given netty event loop.
	 */
	public NettyEventLoop get(EventExecutor eventExecutor) {
		return eventLoopMap.get(eventExecutor);
	}

	/**
	 * Return array of Aerospike event loops.
	 */
	@Override
	public NettyEventLoop[] getArray() {
		return eventLoopArray;
	}

	/**
	 * Return number of event loops in this group.
	 */
	@Override
	public int getSize() {
		return eventLoopArray.length;
	}

	/**
	 * Return Aerospike event loop given array index..
	 */
	@Override
	public NettyEventLoop get(int index) {
		return eventLoopArray[index];
	}

	/**
	 * Return next Aerospike event loop in round-robin fashion.
	 */
	@Override
	public NettyEventLoop next() {
		int iter = eventIter++; // Not atomic by design
		iter = iter % eventLoopArray.length;

		if (iter < 0) {
			iter += eventLoopArray.length;
		}
		return eventLoopArray[iter];
	}

	@Override
	public void close() {
		group.shutdownGracefully();
		/*
		for (NettyEventLoop el : eventLoopArray) {
			el.timer.printRemaining();
		}*/
	}
}
