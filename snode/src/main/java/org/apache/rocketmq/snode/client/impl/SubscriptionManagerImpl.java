/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.snode.client.impl;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.protocol.heartbeat.ConsumeType;
import org.apache.rocketmq.common.protocol.heartbeat.MessageModel;
import org.apache.rocketmq.common.protocol.heartbeat.SubscriptionData;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.remoting.RemotingChannel;
import org.apache.rocketmq.snode.client.SubscriptionManager;

public class SubscriptionManagerImpl implements SubscriptionManager {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.SNODE_LOGGER_NAME);

    private ConcurrentHashMap<String/*Consumer group*/, Subscription> groupSubscriptionTable = new ConcurrentHashMap<>(1024);

    private ConcurrentHashMap<String/*Topic#QueueId*/, Set<RemotingChannel>> pushTable = new ConcurrentHashMap();

    private ConcurrentHashMap<RemotingChannel, Set<String>/*Topic#QueueId*/> clientSubscriptionTable = new ConcurrentHashMap<>(2048);

    private final String pushKeySeparator = "#";

    private String buildPushKey(String topic, Integer queueId) {
        if (topic != null && queueId != null) {
            StringBuffer stringBuffer = new StringBuffer(32);
            stringBuffer.append(topic).append(pushKeySeparator).append(queueId);
            return stringBuffer.toString();
        }
        return null;
    }

    @Override
    public void registerPush(Set<SubscriptionData> subscriptionDataSet, RemotingChannel remotingChannel,
        String groupId) {
        Set<String> prevSubSet = this.clientSubscriptionTable.get(remotingChannel);
        Set<String> keySet = new HashSet<>();
        for (SubscriptionData subscriptionData : subscriptionDataSet) {
            if (subscriptionData.getTopic() != null && subscriptionData.getQueueIdSet() != null && remotingChannel != null) {
                for (Integer queueId : subscriptionData.getQueueIdSet()) {
                    String key = buildPushKey(subscriptionData.getTopic(), queueId);
                    keySet.add(key);
                    Set<RemotingChannel> clientSet = pushTable.get(key);
                    if (clientSet == null) {
                        clientSet = new HashSet<>();
                        Set<RemotingChannel> prev = pushTable.putIfAbsent(key, clientSet);
                        clientSet = prev != null ? prev : clientSet;
                    }
                    log.info("Register push session key: {}, group: {} remoting: {}", key, groupId, remotingChannel.remoteAddress());
                    clientSet.add(remotingChannel);
                }
            }
        }
        if (keySet.size() > 0) {
            this.clientSubscriptionTable.putIfAbsent(remotingChannel, keySet);
        }
        if (prevSubSet != null) {
            for (String key : prevSubSet) {
                if (!keySet.contains(key)) {
                    Set clientSet = pushTable.get(key);
                    if (clientSet != null) {
                        clientSet.remove(remotingChannel);
                        log.info("Remove key:{}", key);
                    }
                }
            }
        }
    }

    @Override
    public void removePush(RemotingChannel remotingChannel) {
        Set<String> subSet = this.clientSubscriptionTable.get(remotingChannel);
        if (subSet != null) {
            for (String key : subSet) {
                Set clientSet = pushTable.get(key);
                if (clientSet != null) {
                    log.info("Remove push key:{} remoting:{}", key, remotingChannel.remoteAddress());
                    clientSet.remove(remotingChannel);
                    if (clientSet.isEmpty()) {
                        pushTable.remove(key);
                    }
                }
            }
        }
        this.clientSubscriptionTable.remove(remotingChannel);
    }

    @Override
    public Set<RemotingChannel> getPushableChannel(String topic, Integer queueId) {
        String key = buildPushKey(topic, queueId);
        log.info("Get pushableChannel by key: {}", key);
        return pushTable.get(key);
    }

    private Subscription getSubscription(String groupId, ConsumeType consumeType,
        MessageModel messageModel, ConsumeFromWhere consumeFromWhere) {
        Subscription subscription = groupSubscriptionTable.get(groupId);
        if (subscription == null) {
            subscription = new Subscription();
            ConcurrentHashMap subscriptionTable = new ConcurrentHashMap<String, SubscriptionData>();
            subscription.setSubscriptionTable(subscriptionTable);
            Subscription prev = groupSubscriptionTable.putIfAbsent(groupId, subscription);
            subscription = prev != null ? prev : subscription;
        }
        subscription.setConsumeFromWhere(consumeFromWhere);
        subscription.setConsumeType(consumeType);
        subscription.setMessageModel(messageModel);
        return subscription;
    }

    private boolean updateSubscribeTopic(Set<SubscriptionData> subscriptionDataSet, Subscription subscription,
        String groupId) {
        boolean updated = false;
        for (SubscriptionData sub : subscriptionDataSet) {
            SubscriptionData old = subscription.getSubscriptionTable().get(sub.getTopic());
            if (old == null) {
                SubscriptionData prev = subscription.getSubscriptionTable().putIfAbsent(sub.getTopic(), sub);
                if (prev == null) {
                    updated = true;
                    log.info("Subscription changed, add new topic, group: {} {}", groupId, sub.toString());
                }
            } else if (sub.getSubVersion() > old.getSubVersion()) {
                if (subscription.getConsumeType() == ConsumeType.CONSUME_PASSIVELY) {
                    log.info("Subscription changed, group: {} OLD: {} NEW: {}",
                        groupId,
                        old.toString(),
                        sub.toString()
                    );
                }
                subscription.getSubscriptionTable().put(sub.getTopic(), sub);
            }
        }

        return updated;
    }

    private boolean removeUnsubscribedTopic(Subscription subscription, Set<SubscriptionData> subscriptionDataSet,
        String groupId) {
        boolean updated = false;

        Iterator<Map.Entry<String, SubscriptionData>> it = subscription.getSubscriptionTable().entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, SubscriptionData> next = it.next();
            String oldTopic = next.getKey();
            boolean exist = false;
            for (SubscriptionData subscriptionData : subscriptionDataSet) {
                if (oldTopic.equals(subscriptionData.getTopic())) {
                    exist = true;
                    break;
                }
            }
            if (!exist) {
                log.warn("Subscription changed, group: {} remove topic {} {}", groupId, oldTopic, next.getValue().toString());
                it.remove();
                updated = true;
            }
        }
        return updated;
    }

    @Override
    public boolean subscribe(String groupId, Set<SubscriptionData> subscriptionDataSet, ConsumeType consumeType,
        MessageModel messageModel, ConsumeFromWhere consumeFromWhere) {
        boolean updated = false;
        if (groupId != null) {
            /*Create new subscription data*/
            Subscription subscription = getSubscription(groupId, consumeType, messageModel, consumeFromWhere);

            /*Update subscribed topic*/
            boolean subscribeUpdated = updateSubscribeTopic(subscriptionDataSet, subscription, groupId);

            /*Remove unsubscribed topic*/
            boolean removedUnsubscribed = removeUnsubscribedTopic(subscription, subscriptionDataSet, groupId);

            updated = subscribeUpdated | removedUnsubscribed;
            subscription.setLastUpdateTimestamp(System.currentTimeMillis());

        }
        return updated;
    }

    @Override
    public void unSubscribe(String groupId, RemotingChannel remotingChannel,
        Set<SubscriptionData> subscriptionDataSet) {

    }

    @Override
    public void cleanSubscription(String groupId, String topic) {

    }

    @Override
    public Subscription getSubscription(String groupId) {
        return groupSubscriptionTable.get(groupId);
    }
}
