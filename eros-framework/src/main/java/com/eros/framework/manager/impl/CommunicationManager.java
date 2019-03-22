package com.eros.framework.manager.impl;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.os.Message;

import com.eros.framework.activity.AbstractWeexActivity;
import com.eros.framework.constant.Constant;
import com.eros.framework.manager.Manager;
import com.eros.framework.manager.ManagerFactory;
import com.eros.framework.manager.impl.dispatcher.DispatchEventManager;
import com.eros.framework.model.AxiosResultBean;

/**
 * Created by liuyuanxiao on 17/12/29.
 */

public class CommunicationManager extends Manager {

    public void sms(String recipients, String params, final Context context) {
        if (TextUtils.isEmpty(recipients)) return;
        Uri smsToUri = Uri.parse("smsto:" + recipients);

        if (context instanceof AbstractWeexActivity) {
            Intent intent = new Intent(Intent.ACTION_SENDTO, smsToUri);
            intent.putExtra("sms_body", params);
            //intent.putExtra(Messaging.KEY_ACTION_SENDTO_EXIT_ON_SENT, true);
            ((AbstractWeexActivity) context).startActivityForResult(intent, Constant.REQUEST_CODE.REQUEST_CODE_SMS);
        } else {
            Intent intent = new Intent(Intent.ACTION_SENDTO, smsToUri);
            intent.putExtra("sms_body", params);
            context.startActivity(intent);
        }
    }

    public void contacts(final Context context) {
        if (context instanceof AbstractWeexActivity) {
            ((AbstractWeexActivity) context).startActivityForResult(new Intent(
                    Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI), Constant.REQUEST_CODE.REQUEST_CODE_CONTRACT);
        }
    }

}
