package com.eros.framework.event;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.net.Uri;
import android.telephony.SmsManager;

import org.json.JSONException;
import org.json.JSONObject;

import com.alibaba.fastjson.JSON;
import com.eros.framework.constant.WXEventCenter;
import com.eros.framework.manager.ManagerFactory;
import com.eros.framework.manager.impl.CommunicationManager;
import com.eros.framework.manager.impl.PermissionManager;
import com.eros.framework.manager.impl.dispatcher.DispatchEventManager;
import com.eros.framework.model.AxiosResultBean;
import com.eros.framework.model.WeexEventBean;
import com.eros.framework.utils.PermissionUtils;
import com.eros.wxbase.EventGate;
import com.squareup.otto.Subscribe;
import com.taobao.weex.bridge.JSCallback;

import java.util.List;
import java.util.ArrayList;

/**
 * Created by liuyuanxiao on 17/12/29.
 */

public class EventCommunication extends EventGate {
    private JSCallback mContactsCallBack;
    private JSCallback mSmsCallBack;
    private ContentObserver mContentObserver;
    private Context mContext;
    private String mRecipients = "";
    private SendReceiver mSendReceiver;
    private DeliverReceiver mDeliverReceiver;
    EventCommunication self;

    @Override
    public void perform(Context context, WeexEventBean weexEventBean, String type) {
        mContext = context;
        if (WXEventCenter.EVENT_COMMUNICATION_SMS.equals(type)) {
            //sms(weexEventBean.getExpand().toString(), weexEventBean.getJsParams(), context, weexEventBean.getJscallback());
            sendSMS(weexEventBean.getExpand().toString(), weexEventBean.getJsParams(), context, weexEventBean.getJscallback());
        } else if (WXEventCenter.EVENT_COMMUNICATION_CONTACTS.equals(type)) {
            contacts(context, weexEventBean.getJscallback());
        }
    }

    public void sms(String recipients, String params, final Context context, JSCallback callback) {
        mRecipients = recipients;
        List<String> rec = JSON.parseArray(recipients, String.class);
        StringBuilder smsList = new StringBuilder();
        for (int i = 0; i < rec.size(); i++) {
            if (i > 0) {
                smsList.append(",");
            }
            smsList.append(rec.get(i));
        }
        mSmsCallBack = callback;
        self = this;

        registerSendReceiver();

        ManagerFactory.getManagerService(DispatchEventManager.class).getBus().register(this);
        CommunicationManager routerManager = ManagerFactory.getManagerService(CommunicationManager.class);
        routerManager.sms(smsList.toString(), params, context);
    }

    public void contacts(final Context context, JSCallback callback) {
        if (!PermissionUtils.checkPermission(context, Manifest.permission.READ_CONTACTS)) {
            return;
        }
        mContactsCallBack = callback;
        ManagerFactory.getManagerService(DispatchEventManager.class).getBus().register(this);
        CommunicationManager routerManager = ManagerFactory.getManagerService(CommunicationManager.class);
        routerManager.contacts(context);
    }

    @Subscribe
    public void contactsResult(AxiosResultBean uploadResultBean) {
        if (uploadResultBean == null || uploadResultBean.header == null) {
            return;
        }
        try {
            JSONObject jsObj = new JSONObject( uploadResultBean.header.toString() );
            final Object meta = jsObj.get("meta");
            if (meta.toString().equals("sms")) {
                if (mSmsCallBack != null) {
                    mSmsCallBack.invoke(uploadResultBean);
                }
                mContext.unregisterReceiver(mSendReceiver);
            } else {
                if (mContactsCallBack != null) {
                    mContactsCallBack.invoke(uploadResultBean);
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        ManagerFactory.getManagerService(DispatchEventManager.class).getBus().unregister(this);
    }

    /**
     * 注册短信观察者，实现短信发送成功的回调
     */
    private void registerContentObserver() {
        Uri uri = Uri.parse("content://sms/");
        final EventCommunication self = this;
        mContentObserver = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                super.onChange(selfChange, uri);
                Cursor cursor = mContext.getContentResolver().query(Uri.parse("content://sms/outbox"),
                        null, null, null, null);
                //遍历查询得到的结果集，即可获取用户正在发送的短信
                while(cursor.moveToNext()){
                    String address = cursor.getString(cursor.getColumnIndex("address"));
                    if (address.equals(mRecipients)) {
                        AxiosResultBean resultBean = new AxiosResultBean();
                        resultBean.status = 0;
                        resultBean.errorMsg = "";
                        mSmsCallBack.invoke(resultBean);
                        ManagerFactory.getManagerService(DispatchEventManager.class).getBus().unregister(self);
                        mContext.getContentResolver().unregisterContentObserver(mContentObserver);
                        break;
                    }
                }
                cursor.close();
            }
        };
        mContext.getContentResolver().registerContentObserver(uri, true, mContentObserver);
    }

    private void sendSMS(String recipients, String params, final Context context, JSCallback callback) {
        mSmsCallBack = callback;
        self = this;

        if (!PermissionUtils.checkPermission(context, Manifest.permission.SEND_SMS)) {
            AxiosResultBean resultBean = new AxiosResultBean();
            resultBean.status = 9;
            resultBean.errorMsg = "Permission deny";
            mSmsCallBack.invoke(resultBean);
            return;
        }

        registerSendReceiver();

        List<String> rec = JSON.parseArray(recipients, String.class);
        StringBuilder smsList = new StringBuilder();
        for (int i = 0; i < rec.size(); i++) {
            if (i > 0) {
                smsList.append(",");
            }
            smsList.append(rec.get(i));
        }

        String address = smsList.toString();
        String body = params;
        //android.telephony.SmsManager, not [android.telephony.gsm.SmsManager]
        SmsManager smsManager = SmsManager.getDefault();
        //短信发送成功或失败后会产生一条SENT_SMS_ACTION的广播
        PendingIntent sendIntent = PendingIntent.getBroadcast(mContext, 0, new Intent("SENT_SMS_ACTION"), 0);
        //接收方成功收到短信后,发送方会产生一条DELIVERED_SMS_ACTION广播
        PendingIntent deliveryIntent = PendingIntent.getBroadcast(mContext, 0, new Intent("DELIVERED_SMS_ACTION"), 0);
        if (body.length() > 70) {	//如果字数超过70,需拆分成多条短信发送
            ArrayList<String> msgs = smsManager.divideMessage(body);

            ArrayList<PendingIntent> sendIntents = new ArrayList<PendingIntent>();
            sendIntents.add(sendIntent);

            ArrayList<PendingIntent> deliveryIntents = new ArrayList<PendingIntent>();
            deliveryIntents.add(deliveryIntent);
            smsManager.sendMultipartTextMessage(address, null, msgs, sendIntents, deliveryIntents);
        } else {
            smsManager.sendTextMessage(address, null, body, sendIntent, deliveryIntent);
        }

        //写入到短信数据源
        ContentValues values = new ContentValues();
        values.put("address",address);	//发送地址
        values.put("body", body);	//消息内容
        values.put("date", System.currentTimeMillis());	//创建时间
        values.put("read", 0);	//0:未读;1:已读
        values.put("type", 2);	//1:接收;2:发送
        mContext.getContentResolver().insert(Uri.parse("content://sms/sent"), values);	//插入数据
        //todo 加一个超时处理
    }

    private class SendReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            AxiosResultBean resultBean = new AxiosResultBean();
            switch (getResultCode()) {
                case Activity.RESULT_OK:
                    resultBean.status = 0;
                    resultBean.errorMsg = "";
                    break;
                default:
                    resultBean.status = 3;
                    resultBean.errorMsg = "Failed";
                    break;
            }
            mSmsCallBack.invoke(resultBean);
            unRegisterSendReceiver();
        }
    }

    private class DeliverReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {

        }
    }

    private void registerSendReceiver() {
        mSendReceiver = new SendReceiver();
        mDeliverReceiver = new DeliverReceiver();
        mContext.registerReceiver(mSendReceiver, new IntentFilter("SENT_SMS_ACTION"));
        mContext.registerReceiver(mDeliverReceiver, new IntentFilter("DELIVERED_SMS_ACTION"));
    }
    private void unRegisterSendReceiver() {
        mContext.unregisterReceiver(mSendReceiver);
        mContext.unregisterReceiver(mDeliverReceiver);

    }

}
