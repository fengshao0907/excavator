package com.googlecode.excavator.message;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.googlecode.excavator.Ring;
import com.googlecode.excavator.constant.LogConstant;

/**
 * 内存消息投递实现
 * @author vlinux
 *
 */
public class MemeryMessager implements Messager {

    private final Logger logger = LoggerFactory.getLogger(LogConstant.MESSAGES);
    
    /*
     * 最多允许一条消息重复投递5次
     */
    private final int MAX_RETRY = 5;

    /*
     * 每次重复投递的惩罚时间步长
     * 最终的惩罚时间为 RETRY * PUNISH_TIME_STEP
     */
    private final long PUNISH_TIME_STEP = 50;

    /*
     * 订阅关系网
     */
    private Map<Class<?>, Set<MessageSubscriber>> subscriptionRelationships = Maps.newHashMap();

    /*
     * 惩罚投递环
     */
    private Ring<Wrapper> punishPostRing = new Ring<Wrapper>();

    /**
     * 惩罚投递封装
     *
     * @author vlinux
     *
     */
    private class Wrapper {

        private final Message<?> message;   //消息
        private final long expirt;          //到期时间

        public Wrapper(Message<?> message, long expirt) {
            this.message = message;
            this.expirt = expirt;
        }

    }

    /**
     * 惩罚消息投递员
     */
    private Thread deamon = new Thread("message-punish-deamon") {

        @Override
        public void run() {
            while (true) {
                try {
                    Thread.sleep(500);
                    if (punishPostRing.isEmpty()) {
                        continue;
                    }
                    final long now = System.currentTimeMillis();
                    Iterator<Wrapper> it = punishPostRing.iterator();
                    while (it.hasNext()) {
                        Wrapper wrapper = it.next();
                        if (now < wrapper.expirt) {
                            // 时间没到，跳过
                            continue;
                        } else {
                            // 时间到了，可以再次投递了
                            it.remove();
                            normalPost(wrapper.message);
                        }
                    }
                } catch (Throwable t) {
                    logger.warn("punish post message failed!", t);
                }

            }
        }

    };

    /**
     * 构造函数
     */
    public MemeryMessager() {
        deamon.setDaemon(true);
        deamon.start();
    }
    
    /**
     * 投递消息
     *
     * @param msg
     */
    public void post(Message<?> msg) {
        if (null == msg) {
            return;
        }

        int reTry = msg.getReTry();
        // 超过投递重试次数，主动放弃
        if (MAX_RETRY <= reTry) {
            return;
        }

        msg.inc();

        // 第一次来的同学，走普通投递
        if (reTry == 0) {
            normalPost(msg);
        } // 否则请老老实实走延时惩罚投递
        else {
            punishPost(msg);
        }

    }

    /**
     * 普通投递
     *
     * @param msg
     */
    private void normalPost(Message<?> msg) {

        final Class<?> clazz = msg.getClass();
        final Set<MessageSubscriber> subscribers = subscriptionRelationships.get(clazz);
        if (CollectionUtils.isEmpty(subscribers)) {
            return;
        }
        final Iterator<MessageSubscriber> subIt = subscribers.iterator();
        while (subIt.hasNext()) {
            MessageSubscriber subscriber = subIt.next();
            try {
                subscriber.receive(msg);
            } catch (Throwable t) {
              logger.warn("post msg:{} to subscriber:{} failed.", new Object[]{msg, subscriber.getClass().getSimpleName(), t});
                // 投递失败，主动再次投递，以惩罚
                post(msg);
            }
        }

    }

    /**
     * 惩罚投递
     *
     * @param msg
     */
    private void punishPost(Message<?> msg) {

        long now = System.currentTimeMillis();
        long punishTime = msg.getReTry() * PUNISH_TIME_STEP;

        punishPostRing.insert(new Wrapper(msg, now + punishTime));

    }

    /**
     * 注册订阅关系
     *
     * @param subscriber
     * @param msgTypes
     */
    public synchronized void register(MessageSubscriber subscriber, Class<?>... msgTypes) {
        if (ArrayUtils.isEmpty(msgTypes)
                || null == subscriber) {
            return;
        }
        for (Class<?> clazz : msgTypes) {
            final Set<MessageSubscriber> subscribers;
            if (!subscriptionRelationships.containsKey(clazz)) {
                subscribers = Sets.newHashSet();
                subscriptionRelationships.put(clazz, subscribers);
            } else {
                subscribers = subscriptionRelationships.get(clazz);
            }//if
            subscribers.add(subscriber);
        }
    }
    
}
