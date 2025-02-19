/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.smartloli.kafka.eagle.web.quartz.shard.task.sub;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.smartloli.kafka.eagle.api.im.IMFactory;
import org.smartloli.kafka.eagle.api.im.IMService;
import org.smartloli.kafka.eagle.api.im.IMServiceImpl;
import org.smartloli.kafka.eagle.common.protocol.alarm.AlarmClusterInfo;
import org.smartloli.kafka.eagle.common.protocol.alarm.AlarmConfigInfo;
import org.smartloli.kafka.eagle.common.protocol.alarm.AlarmMessageInfo;
import org.smartloli.kafka.eagle.common.protocol.topic.TopicLogSize;
import org.smartloli.kafka.eagle.common.util.*;
import org.smartloli.kafka.eagle.common.util.KConstants.AlarmType;
import org.smartloli.kafka.eagle.core.metrics.KafkaMetricsFactory;
import org.smartloli.kafka.eagle.core.metrics.KafkaMetricsService;
import org.smartloli.kafka.eagle.web.controller.StartupListener;
import org.smartloli.kafka.eagle.web.service.impl.AlertServiceImpl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Alert cluster metrics sub task.
 *
 * @author smartloli.
 * <p>
 * Created by Dec 09, 2021
 */
public class AlertClusterSubTask extends Thread {

    // get kafka metrics dataset
    private KafkaMetricsService kafkaMetricsService = new KafkaMetricsFactory().create();

    @Override
    public synchronized void run() {
        // run cluster metrics job
        Cluster cluster = new Cluster();
        cluster.cluster();
    }

    class Cluster {

        public void cluster() {
            AlertServiceImpl alertService = StartupListener.getBean("alertServiceImpl", AlertServiceImpl.class);
            for (AlarmClusterInfo cluster : alertService.getAllAlarmClusterTasks()) {
                if (KConstants.AlarmType.DISABLE.equals(cluster.getIsEnable())) {
                    break;
                }
                String alarmGroup = cluster.getAlarmGroup();
                Map<String, Object> params = new HashMap<>();
                params.put("cluster", cluster.getCluster());
                params.put("alarmGroup", alarmGroup);
                AlarmConfigInfo alarmConfig = alertService.getAlarmConfigByGroupName(params);
                if (AlarmType.TOPIC.equals(cluster.getType())) {
                    JSONObject topicAlarmJson = JSON.parseObject(cluster.getServer());
                    String topic = topicAlarmJson.getString("topic");
                    long alarmCapacity = topicAlarmJson.getLong("capacity");
                    long realCapacity = 0L;
                    try {
                        realCapacity = kafkaMetricsService.topicCapacity(cluster.getCluster(), topic);
                    } catch (Exception e) {
                        LoggerUtils.print(this.getClass()).error("Get topic capacity has error, msg is ", e);
                    }
                    JSONObject alarmTopicMsg = new JSONObject();
                    alarmTopicMsg.put("topic", topic);
                    alarmTopicMsg.put("alarmCapacity", alarmCapacity);
                    alarmTopicMsg.put("realCapacity", realCapacity);
                    if (realCapacity > alarmCapacity && (cluster.getAlarmTimes() < cluster.getAlarmMaxTimes() || cluster.getAlarmMaxTimes() == -1)) {
                        cluster.setAlarmTimes(cluster.getAlarmTimes() + 1);
                        cluster.setIsNormal("N");
                        alertService.modifyClusterStatusAlertById(cluster);
                        try {
                            sendAlarmClusterError(alarmConfig, cluster, alarmTopicMsg.toJSONString());
                        } catch (Exception e) {
                            LoggerUtils.print(this.getClass()).error("Send alarm cluser exception has error, msg is ", e);
                        }
                    } else if (realCapacity < alarmCapacity) {
                        if (cluster.getIsNormal().equals("N")) {
                            cluster.setIsNormal("Y");
                            // clear error alarm and reset
                            cluster.setAlarmTimes(0);
                            // notify the cancel of the alarm
                            alertService.modifyClusterStatusAlertById(cluster);
                            try {
                                sendAlarmClusterNormal(alarmConfig, cluster, alarmTopicMsg.toJSONString());
                            } catch (Exception e) {
                                LoggerUtils.print(this.getClass()).error("Send alarm cluser normal has error, msg is ", e);
                            }
                        }
                    }
                } else if (AlarmType.PRODUCER.equals(cluster.getType())) {
                    JSONObject producerAlarmJson = JSON.parseObject(cluster.getServer());
                    String topic = producerAlarmJson.getString("topic");
                    String[] speeds = producerAlarmJson.getString("speed").split(",");
                    long startSpeed = 0L;
                    long endSpeed = 0L;
                    if (speeds.length == 2) {
                        startSpeed = Long.parseLong(speeds[0]);
                        endSpeed = Long.parseLong(speeds[1]);
                    }
                    Map<String, Object> producerSpeedParams = new HashMap<>();
                    producerSpeedParams.put("cluster", cluster.getCluster());
                    producerSpeedParams.put("topic", topic);
                    producerSpeedParams.put("stime", CalendarUtils.getCustomDate("yyyyMMdd"));
                    List<TopicLogSize> topicLogSizes = alertService.queryTopicProducerByAlarm(producerSpeedParams);
                    long realSpeed = 0;
                    if (topicLogSizes != null && topicLogSizes.size() > 0) {
                        realSpeed = topicLogSizes.get(0).getDiffval();
                    }

                    JSONObject alarmTopicMsg = new JSONObject();
                    alarmTopicMsg.put("topic", topic);
                    alarmTopicMsg.put("alarmSpeeds", startSpeed + "," + endSpeed);
                    alarmTopicMsg.put("realSpeeds", realSpeed);
                    if ((realSpeed < startSpeed || realSpeed > endSpeed) && (cluster.getAlarmTimes() < cluster.getAlarmMaxTimes() || cluster.getAlarmMaxTimes() == -1)) {
                        cluster.setAlarmTimes(cluster.getAlarmTimes() + 1);
                        cluster.setIsNormal("N");
                        alertService.modifyClusterStatusAlertById(cluster);
                        try {
                            sendAlarmClusterError(alarmConfig, cluster, alarmTopicMsg.toJSONString());
                        } catch (Exception e) {
                            LoggerUtils.print(this.getClass()).error("Send alarm cluser exception has error, msg is ", e);
                        }
                    } else if (realSpeed >= startSpeed && realSpeed <= endSpeed) {
                        if (cluster.getIsNormal().equals("N")) {
                            cluster.setIsNormal("Y");
                            // clear error alarm and reset
                            cluster.setAlarmTimes(0);
                            // notify the cancel of the alarm
                            alertService.modifyClusterStatusAlertById(cluster);
                            try {
                                sendAlarmClusterNormal(alarmConfig, cluster, alarmTopicMsg.toJSONString());
                            } catch (Exception e) {
                                LoggerUtils.print(this.getClass()).error("Send alarm cluser normal has error, msg is ", e);
                            }
                        }
                    }
                } else {
                    String[] servers = cluster.getServer().split(",");
                    List<String> errorServers = new ArrayList<String>();
                    List<String> normalServers = new ArrayList<String>();
                    for (String server : servers) {
                        String host = server.split(":")[0];
                        int port = 0;
                        try {
                            port = Integer.parseInt(server.split(":")[1]);
                            boolean status = NetUtils.telnet(host, port);
                            if (!status) {
                                errorServers.add(server);
                            } else {
                                normalServers.add(server);
                            }
                        } catch (Exception e) {
                            LoggerUtils.print(this.getClass()).error("Alarm cluster has error, msg is ", e);
                        }
                    }
                    if (errorServers.size() > 0 && (cluster.getAlarmTimes() < cluster.getAlarmMaxTimes() || cluster.getAlarmMaxTimes() == -1)) {
                        cluster.setAlarmTimes(cluster.getAlarmTimes() + 1);
                        cluster.setIsNormal("N");
                        alertService.modifyClusterStatusAlertById(cluster);
                        try {
                            sendAlarmClusterError(alarmConfig, cluster, errorServers.toString());
                        } catch (Exception e) {
                            LoggerUtils.print(this.getClass()).error("Send alarm cluster exception has error, msg is ", e);
                        }
                    } else if (errorServers.size() == 0) {
                        if (cluster.getIsNormal().equals("N")) {
                            cluster.setIsNormal("Y");
                            // clear error alarm and reset
                            cluster.setAlarmTimes(0);
                            // notify the cancel of the alarm
                            alertService.modifyClusterStatusAlertById(cluster);
                            try {
                                sendAlarmClusterNormal(alarmConfig, cluster, normalServers.toString());
                            } catch (Exception e) {
                                LoggerUtils.print(this.getClass()).error("Send alarm cluster normal has error, msg is ", e);
                            }
                        }
                    }
                }
            }
        }

        private void sendAlarmClusterError(AlarmConfigInfo alarmConfing, AlarmClusterInfo cluster, String server) {
            if (alarmConfing.getAlarmType().equals(AlarmType.EMAIL)) {
                AlarmMessageInfo alarmMsg = new AlarmMessageInfo();
                alarmMsg.setAlarmId(cluster.getId());
                alarmMsg.setAlarmCluster(alarmConfing.getCluster());
                alarmMsg.setTitle("EFAK - Alert Cluster Error");
                if (KConstants.AlarmType.TOPIC.equals(cluster.getType())) {
                    JSONObject alarmTopicMsg = JSON.parseObject(server);
                    String topic = alarmTopicMsg.getString("topic");
                    long alarmCapacity = alarmTopicMsg.getLong("alarmCapacity");
                    long realCapacity = alarmTopicMsg.getLong("realCapacity");
                    alarmMsg.setAlarmContent("topic.capacity.overflow [topic(" + topic + "), real.capacity(" + StrUtils.stringify(realCapacity) + "), alarm.capacity(" + StrUtils.stringify(alarmCapacity) + ")]");
                } else if (KConstants.AlarmType.PRODUCER.equals(cluster.getType())) {
                    JSONObject alarmTopicMsg = JSON.parseObject(server);
                    String topic = alarmTopicMsg.getString("topic");
                    String alarmSpeeds = alarmTopicMsg.getString("alarmSpeeds");
                    long realSpeeds = alarmTopicMsg.getLong("realSpeeds");
                    alarmMsg.setAlarmContent("producer.speed.overflow [topic(" + topic + "), real.speeds(" + realSpeeds + "), alarm.speeds.range(" + alarmSpeeds + ")]");
                } else {
                    alarmMsg.setAlarmContent("node.shutdown [ " + server + " ]");
                }
                alarmMsg.setAlarmDate(CalendarUtils.getDate());
                alarmMsg.setAlarmLevel(cluster.getAlarmLevel());
                alarmMsg.setAlarmProject(cluster.getType());
                alarmMsg.setAlarmStatus("PROBLEM");
                alarmMsg.setAlarmTimes("current(" + cluster.getAlarmTimes() + "), max(" + cluster.getAlarmMaxTimes() + ")");
                IMService im = new IMFactory().create();
                JSONObject object = new JSONObject();
                object.put("address", alarmConfing.getAlarmAddress());
                if (JSONUtils.isJsonObject(alarmConfing.getAlarmUrl())) {
                    object.put("msg", alarmMsg.toMailJSON());
                } else {
                    object.put("msg", alarmMsg.toMail());
                }
                object.put("title", alarmMsg.getTitle());
                im.sendPostMsgByMail(object.toJSONString(), alarmConfing.getAlarmUrl());
            } else if (alarmConfing.getAlarmType().equals(AlarmType.DingDing)) {
                AlarmMessageInfo alarmMsg = new AlarmMessageInfo();
                alarmMsg.setAlarmId(cluster.getId());
                alarmMsg.setAlarmCluster(alarmConfing.getCluster());
                alarmMsg.setTitle("EFAK - Alert Cluster Error");
                if (AlarmType.TOPIC.equals(cluster.getType())) {
                    JSONObject alarmTopicMsg = JSON.parseObject(server);
                    String topic = alarmTopicMsg.getString("topic");
                    long alarmCapacity = alarmTopicMsg.getLong("alarmCapacity");
                    long realCapacity = alarmTopicMsg.getLong("realCapacity");
                    alarmMsg.setAlarmContent("topic.capacity.overflow [topic(" + topic + "), real.capacity(" + StrUtils.stringify(realCapacity) + "), alarm.capacity(" + StrUtils.stringify(alarmCapacity) + ")]");
                } else if (AlarmType.PRODUCER.equals(cluster.getType())) {
                    JSONObject alarmTopicMsg = JSON.parseObject(server);
                    String topic = alarmTopicMsg.getString("topic");
                    String alarmSpeeds = alarmTopicMsg.getString("alarmSpeeds");
                    long realSpeeds = alarmTopicMsg.getLong("realSpeeds");
                    alarmMsg.setAlarmContent("producer.speed.overflow [topic(" + topic + "), real.speeds(" + realSpeeds + "), alarm.speeds.range(" + alarmSpeeds + ")]");
                } else {
                    alarmMsg.setAlarmContent("node.shutdown [ " + server + " ]");
                }
                alarmMsg.setAlarmDate(CalendarUtils.getDate());
                alarmMsg.setAlarmLevel(cluster.getAlarmLevel());
                alarmMsg.setAlarmProject(cluster.getType());
                alarmMsg.setAlarmStatus("PROBLEM");
                alarmMsg.setAlarmTimes("current(" + cluster.getAlarmTimes() + "), max(" + cluster.getAlarmMaxTimes() + ")");
                IMService im = new IMFactory().create();
                im.sendPostMsgByDingDing(alarmMsg.toDingDingMarkDown(), alarmConfing.getAlarmUrl());
            } else if (alarmConfing.getAlarmType().equals(AlarmType.WeChat)) {
                AlarmMessageInfo alarmMsg = new AlarmMessageInfo();
                alarmMsg.setAlarmId(cluster.getId());
                alarmMsg.setAlarmCluster(alarmConfing.getCluster());
                alarmMsg.setTitle("`EFAK - Alert Cluster Error`\n");
                if (AlarmType.TOPIC.equals(cluster.getType())) {
                    JSONObject alarmTopicMsg = JSON.parseObject(server);
                    String topic = alarmTopicMsg.getString("topic");
                    long alarmCapacity = alarmTopicMsg.getLong("alarmCapacity");
                    long realCapacity = alarmTopicMsg.getLong("realCapacity");
                    alarmMsg.setAlarmContent("<font color=\"warning\">topic.capacity.overflow [topic(" + topic + "), real.capacity(" + StrUtils.stringify(realCapacity) + "), alarm.capacity(" + StrUtils.stringify(alarmCapacity) + ")]</font>");
                } else if (AlarmType.PRODUCER.equals(cluster.getType())) {
                    JSONObject alarmTopicMsg = JSON.parseObject(server);
                    String topic = alarmTopicMsg.getString("topic");
                    String alarmSpeeds = alarmTopicMsg.getString("alarmSpeeds");
                    long realSpeeds = alarmTopicMsg.getLong("realSpeeds");
                    alarmMsg.setAlarmContent("<font color=\"warning\">producer.speed.overflow [topic(" + topic + "), real.speeds(" + realSpeeds + "), alarm.speeds.range(" + alarmSpeeds + ")]</font>");
                } else {
                    alarmMsg.setAlarmContent("<font color=\"warning\">node.shutdown [ " + server + " ]</font>");
                }
                alarmMsg.setAlarmDate(CalendarUtils.getDate());
                alarmMsg.setAlarmLevel(cluster.getAlarmLevel());
                alarmMsg.setAlarmProject(cluster.getType());
                alarmMsg.setAlarmStatus("<font color=\"warning\">PROBLEM</font>");
                alarmMsg.setAlarmTimes("current(" + cluster.getAlarmTimes() + "), max(" + cluster.getAlarmMaxTimes() + ")");
                IMServiceImpl im = new IMServiceImpl();
                im.sendPostMsgByWeChat(alarmMsg.toWeChatMarkDown(), alarmConfing.getAlarmUrl());
            }
        }

        private void sendAlarmClusterNormal(AlarmConfigInfo alarmConfing, AlarmClusterInfo cluster, String server) {
            if (alarmConfing.getAlarmType().equals(AlarmType.EMAIL)) {
                AlarmMessageInfo alarmMsg = new AlarmMessageInfo();
                alarmMsg.setAlarmId(cluster.getId());
                alarmMsg.setAlarmCluster(alarmConfing.getCluster());
                alarmMsg.setTitle("EFAK - Alert Cluster Notice");
                if (AlarmType.TOPIC.equals(cluster.getType())) {
                    JSONObject alarmTopicMsg = JSON.parseObject(server);
                    String topic = alarmTopicMsg.getString("topic");
                    long alarmCapacity = alarmTopicMsg.getLong("alarmCapacity");
                    long realCapacity = alarmTopicMsg.getLong("realCapacity");
                    alarmMsg.setAlarmContent("topic.capacity.normal [topic(" + topic + "), real.capacity(" + StrUtils.stringify(realCapacity) + "), alarm.capacity(" + StrUtils.stringify(alarmCapacity) + ")]");
                } else if (AlarmType.PRODUCER.equals(cluster.getType())) {
                    JSONObject alarmTopicMsg = JSON.parseObject(server);
                    String topic = alarmTopicMsg.getString("topic");
                    String alarmSpeeds = alarmTopicMsg.getString("alarmSpeeds");
                    long realSpeeds = alarmTopicMsg.getLong("realSpeeds");
                    alarmMsg.setAlarmContent("producer.speed.normal [topic(" + topic + "), real.speeds(" + realSpeeds + "), alarm.speeds.range(" + alarmSpeeds + ")]");
                } else {
                    alarmMsg.setAlarmContent("node.alive [ " + server + " ]");
                }
                alarmMsg.setAlarmDate(CalendarUtils.getDate());
                alarmMsg.setAlarmLevel(cluster.getAlarmLevel());
                alarmMsg.setAlarmProject(cluster.getType());
                alarmMsg.setAlarmStatus("NORMAL");
                alarmMsg.setAlarmTimes("current(" + cluster.getAlarmTimes() + "), max(" + cluster.getAlarmMaxTimes() + ")");
                IMService im = new IMFactory().create();
                JSONObject object = new JSONObject();
                object.put("address", alarmConfing.getAlarmAddress());
                object.put("title", alarmMsg.getTitle());
                if (JSONUtils.isJsonObject(alarmConfing.getAlarmUrl())) {
                    object.put("msg", alarmMsg.toMailJSON());
                } else {
                    object.put("msg", alarmMsg.toMail());
                }
                im.sendPostMsgByMail(object.toJSONString(), alarmConfing.getAlarmUrl());
            } else if (alarmConfing.getAlarmType().equals(AlarmType.DingDing)) {
                AlarmMessageInfo alarmMsg = new AlarmMessageInfo();
                alarmMsg.setAlarmId(cluster.getId());
                alarmMsg.setAlarmCluster(alarmConfing.getCluster());
                alarmMsg.setTitle("EFAK - Alert Cluster Notice");
                if (AlarmType.TOPIC.equals(cluster.getType())) {
                    JSONObject alarmTopicMsg = JSON.parseObject(server);
                    String topic = alarmTopicMsg.getString("topic");
                    long alarmCapacity = alarmTopicMsg.getLong("alarmCapacity");
                    long realCapacity = alarmTopicMsg.getLong("realCapacity");
                    alarmMsg.setAlarmContent("topic.capacity.normal [topic(" + topic + "), real.capacity(" + StrUtils.stringify(realCapacity) + "), alarm.capacity(" + StrUtils.stringify(alarmCapacity) + ")]");
                } else if (AlarmType.PRODUCER.equals(cluster.getType())) {
                    JSONObject alarmTopicMsg = JSON.parseObject(server);
                    String topic = alarmTopicMsg.getString("topic");
                    String alarmSpeeds = alarmTopicMsg.getString("alarmSpeeds");
                    long realSpeeds = alarmTopicMsg.getLong("realSpeeds");
                    alarmMsg.setAlarmContent("producer.speed.normal [topic(" + topic + "), real.speeds(" + realSpeeds + "), alarm.speeds.range(" + alarmSpeeds + ")]");
                } else {
                    alarmMsg.setAlarmContent("node.alive [ " + server + " ]");
                }
                alarmMsg.setAlarmDate(CalendarUtils.getDate());
                alarmMsg.setAlarmLevel(cluster.getAlarmLevel());
                alarmMsg.setAlarmProject(cluster.getType());
                alarmMsg.setAlarmStatus("NORMAL");
                alarmMsg.setAlarmTimes("current(" + cluster.getAlarmTimes() + "), max(" + cluster.getAlarmMaxTimes() + ")");
                IMService im = new IMFactory().create();
                im.sendPostMsgByDingDing(alarmMsg.toDingDingMarkDown(), alarmConfing.getAlarmUrl());
            } else if (alarmConfing.getAlarmType().equals(AlarmType.WeChat)) {
                AlarmMessageInfo alarmMsg = new AlarmMessageInfo();
                alarmMsg.setAlarmId(cluster.getId());
                alarmMsg.setAlarmCluster(alarmConfing.getCluster());
                alarmMsg.setTitle("`EFAK - Alert Cluster Notice`\n");
                if (AlarmType.TOPIC.equals(cluster.getType())) {
                    JSONObject alarmTopicMsg = JSON.parseObject(server);
                    String topic = alarmTopicMsg.getString("topic");
                    long alarmCapacity = alarmTopicMsg.getLong("alarmCapacity");
                    long realCapacity = alarmTopicMsg.getLong("realCapacity");
                    alarmMsg.setAlarmContent("<font color=\"#008000\">topic.capacity.normal [topic(" + topic + "), real.capacity(" + StrUtils.stringify(realCapacity) + "), alarm.capacity(" + StrUtils.stringify(alarmCapacity) + ")]</font>");
                } else if (AlarmType.PRODUCER.equals(cluster.getType())) {
                    JSONObject alarmTopicMsg = JSON.parseObject(server);
                    String topic = alarmTopicMsg.getString("topic");
                    String alarmSpeeds = alarmTopicMsg.getString("alarmSpeeds");
                    long realSpeeds = alarmTopicMsg.getLong("realSpeeds");
                    alarmMsg.setAlarmContent("<font color=\"#008000\">producer.speed.normal [topic(" + topic + "), real.speeds(" + realSpeeds + "), alarm.speeds.range(" + alarmSpeeds + ")]</font>");
                } else {
                    alarmMsg.setAlarmContent("<font color=\"#008000\">node.alive [ " + server + " ]</font>");
                }
                alarmMsg.setAlarmDate(CalendarUtils.getDate());
                alarmMsg.setAlarmLevel(cluster.getAlarmLevel());
                alarmMsg.setAlarmProject(cluster.getType());
                alarmMsg.setAlarmStatus("<font color=\"#008000\">NORMAL</font>");
                alarmMsg.setAlarmTimes("current(" + cluster.getAlarmTimes() + "), max(" + cluster.getAlarmMaxTimes() + ")");
                IMServiceImpl im = new IMServiceImpl();
                im.sendPostMsgByWeChat(alarmMsg.toWeChatMarkDown(), alarmConfing.getAlarmUrl());
            }
        }
    }
}
