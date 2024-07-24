package com.wwb.proto;

import org.apache.commons.text.StringEscapeUtils;

public class PBMain{
    public static void main(String[] args) {
        PBDecoder pbDecoder = new PBDecoder();
        pbDecoder.showDialog();
    }

    public static String forJeb(String strInfo,String objstr){
        PBDecoder pbDecoder = new PBDecoder();

//        String originstr = messageinfo.replace(");","").replace("new Object[]","");
//        String strInfo = originstr.substring(0,originstr.indexOf(","));
//        String objstr = originstr.substring(originstr.indexOf(",")+1);
//
//        strInfo = strInfo.replace("\"","").replace(" ","");
//        strInfo = StringEscapeUtils.unescapeJava(strInfo);

//        objstr = objstr.replace("\"","").replace("{","").replace("}","");
        String[] objects = objstr.split(",");
        String parseStr = pbDecoder.dumpProtoBuffNew(strInfo, objects);

        String protoStr;
        try{
            protoStr = pbDecoder.ToProto(pbDecoder.UpdateType(parseStr,objects),objstr);
        }catch (Exception ec){
            protoStr = "发生内部错误";
        }
        return protoStr;
    }

    public static String test(){
        return "hello world";
    }
}