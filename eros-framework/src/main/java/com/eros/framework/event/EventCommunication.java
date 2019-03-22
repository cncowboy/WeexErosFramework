package com.eros.framework.event;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.net.Uri;

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
    private EventCommunication self;
    private long mStartTimeOfShare2Msg = 0;
    private long mStartTimeOfShare2MsgTemp = 0;
    private long mRecvContentObserverCount = -1;

    @Override
    public void perform(Context context, WeexEventBean weexEventBean, String type) {
        mContext = context;
        if (WXEventCenter.EVENT_COMMUNICATION_SMS.equals(type)) {
            sms(weexEventBean.getExpand().toString(), weexEventBean.getJsParams(), context, weexEventBean.getJscallback());
            //sendSMS(weexEventBean.getExpand().toString(), weexEventBean.getJsParams(), context, weexEventBean.getJscallback());
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

        mStartTimeOfShare2Msg = System.currentTimeMillis();
        mRecvContentObserverCount = 0;
        mStartTimeOfShare2MsgTemp = -1;
        registerContentObserver();

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
                unRegisterContentObserver();
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
                mRecvContentObserverCount++;
                long endTimeOfShare2Msg = System.currentTimeMillis();
                long dt = endTimeOfShare2Msg - mStartTimeOfShare2Msg;
                if ((mStartTimeOfShare2Msg != mStartTimeOfShare2MsgTemp && mRecvContentObserverCount > 2) && dt <= 20000) {//用户触发短信分享动作并在20s内有短信发出，就认定是短信发送成功。
                    mStartTimeOfShare2MsgTemp = mStartTimeOfShare2Msg;

                    AxiosResultBean resultBean = new AxiosResultBean();
                    resultBean.status = 0;
                    resultBean.errorMsg = "";
                    mSmsCallBack.invoke(resultBean);

                    ManagerFactory.getManagerService(DispatchEventManager.class).getBus().unregister(self);
                    unRegisterContentObserver();
                }
            }
        };
        //mContext.getContentResolver().registerContentObserver(uri, true, mContentObserver);
    }

    private void unRegisterContentObserver() {
        //mContext.getContentResolver().unregisterContentObserver(mContentObserver);
    }

}
