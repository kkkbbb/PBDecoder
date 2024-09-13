# -*- coding: utf-8 -*-

from com.pnfsoftware.jeb.client.api import IScript
from com.pnfsoftware.jeb.core.units.code import ICodeUnit
from com.pnfsoftware.jeb.core.units.code.android import IDexUnit
from com.pnfsoftware.jeb.core.units.code.android.dex import IDexCodeItem
from com.pnfsoftware.jeb.client.api import FormEntry
from java.lang import System
import sys
import os

current_dir = os.path.dirname(os.path.abspath(__file__))
jar_path = os.path.join(current_dir, "PBDecoder.jar")

if os.path.exists(jar_path):
  sys.path.append(jar_path)
else:
  sys.path.append(r"D:\tools\PBDecoder.jar")

System.setProperty("python.security.respectJavaAccessibility", "false")
from com.wwb.proto import PBMain

dexUnit = None
parsedClass = []
    
class protoParser(IScript):
  def run(self, ctx):
    global dexUnit,parsedClass
    prj = ctx.getMainProject()
    dexUnit = prj.findUnit(IDexUnit)
    parsedClass = []
    
    className = ctx.displayForm("proto class","the class of protobuf",
    FormEntry.Text('className', '', FormEntry.INLINE, None, 0, 0))[0]
    protoresult = self.parseCls(dexUnit.getClass("L"+className+";"))
    ctx.displayText("proto", protoresult,True)
    
  def parseCls(self,cls):
    parsedClass.append(cls.getName())
    currentproto = self.parseProto(cls)
    
    cresultstr = "message " + cls.getName() + " {\n"
    subresult = ""
    for fields in currentproto.split("\n"):
      if not len(fields) > 0: continue
      if "{" in fields or "}" in fields:
        cresultstr +="\t"+fields+"\n"
        continue
      field = fields.split("=")[0].split(" ")
      mfieldType = field[1] if not "oneof" in field[1] else field[0]
      mfieldType = mfieldType.strip()
      if mfieldType == "message" or mfieldType == "group":
        for clsField in cls.getFields():
          if clsField.getName(True) == field[2] or clsField.getName(False) == field[2]:
            mtype = clsField.getFieldType()
            cresultstr += "\t" + fields.replace(mfieldType,mtype.getName()) + "\n"
            if mtype.getName() in parsedClass: continue
            subresult += self.parseCls(mtype.getImplementingClass())
        continue
      
      if mfieldType == "enum":
        fields = fields.replace("enum","int32") + " //unknow enum"
      if "/" in mfieldType:
        fields = fields.replace(mfieldType,mfieldType.split("/")[1])
      cresultstr +="\t"+fields+"\n"
      
      if not self.isBaseType(mfieldType):
        if "/" in mfieldType:
          if mfieldType.split("/")[1] in parsedClass: continue
        if mfieldType in parsedClass: continue
        subresult += self.parseCls(dexUnit.getClass("L"+mfieldType+";"))
    
    return cresultstr+"}\n\n"+subresult
  
  def isBaseType(self,mtype):
    for basetype in ["enum","string","int","double","float","bool","fixed","bytes","oneof","map","group"]:
      if basetype in mtype:
        return True
    return False
    

  def parseProto(self,cls):
    for method in cls.getMethods():
      if method.getName() == "<init>" or method.getName() == "<clinit>": continue
      codeItem = method.getData().getCodeItem()
      
      objs = {}
      messageinfo = ""
      objkeys = []
      aputobjs = {}
      constRegs = {}
      constRegsComplete = False
      if isinstance(codeItem, IDexCodeItem):
        instructions = codeItem.getInstructions()
        for ins in instructions:
          if ins.getMnemonic() == "const/4":
            if constRegsComplete == 0: constRegs[ins.getOperand(0).getValue()] = ins.getOperand(1).getValue()
            continue
          if "if-eq" == ins.getMnemonic():
            if constRegs[ins.getOperand(1).getValue()] == 2:
              constRegsComplete = (ins.getOperand(2).getValue())*2 + ins.getOffset()
              break
          if ins.isSwitch():
            for item in ins.getSwitchData().getElements():
              if item[0] == 2:
                constRegsComplete = item[1]*2 + ins.getOffset()
                break
            break

        if constRegsComplete > 0:
          for firststr,ins in enumerate(instructions):
            if ins.getOffset() == constRegsComplete:
              break
        else:
          continue
        
        if firststr == len(instructions)-1: continue  #inccorect method!
        objcomplete = False
        while True:
          ins = instructions[firststr]
          firststr+=1
          # print ins
          if ins.getMnemonic() == "const-string":
            conststr = dexUnit.getString(ins.getOperand(1).getValue()).getValue()
            if "\x01" in conststr or "\x02" in conststr or "\x03" in conststr or "\x00" in conststr:
              messageinfo = conststr
            else:
              if not objcomplete: objs[ins.getOperand(0).getValue()]=conststr
            continue
          if ins.getMnemonic() == "const-class":
            # print dexUnit.getType(ins.getOperand(1).getValue())
            if not objcomplete: objs[ins.getOperand(0).getValue()]= dexUnit.getType(ins.getOperand(1).getValue()).getAddress()[1:-1]
            continue
          if "const/" in ins.getMnemonic():
            objs[ins.getOperand(0).getValue()]= ins.getOperand(1).getValue()
            continue
          if ins.getMnemonic() == "sget-object":
            if not objcomplete: objs[ins.getOperand(0).getValue()]="enum.type"
            continue
          if "move-object" in ins.getMnemonic():
            if not objcomplete: objs[ins.getOperand(0).getValue()]=objs[ins.getOperand(1).getValue()]
            continue
          if "filled-new-array" in ins.getMnemonic():
            if "range" in ins.getMnemonic():
              objkeys = sorted(objs.keys())
            else: objkeys =[item.getValue() for item in ins.getOperands()[1:]]
            objcomplete = True
            continue
          if "aput-object" == ins.getMnemonic():
            key = ins.getOperand(2).getValue()
            key  = objs[key] if key in objs else constRegs[key]
            # print objs
            aputobjs[key] =  objs[ins.getOperand(0).getValue()]
            # objs.clear()
            continue
          # if ("invoke" in ins.getMnemonic() and len(messageinfo)>=2):
          #   break
          if "move-result" in ins.getMnemonic():
            objs[ins.getOperand(0).getValue()] = "enum.type"
            continue
          if firststr >= len(instructions)-1:
            break
        # print messageinfo
        if len(messageinfo) < 2: continue
        else: break
            
    # print cls,method
    if len(messageinfo) < 2: raise Exception("Unexcept messageinfo!")
    if len(objs) < 1: return ""
    if aputobjs: 
      objs = aputobjs
      objkeys = sorted(aputobjs.keys())
    # print self.to_unicode_escape(messageinfo),"\n",''.join(objs[key]+"," for key in objkeys)
    return PBMain.forJeb(self.to_unicode_escape(messageinfo),''.join(objs[key]+"," for key in objkeys))
      
  def to_unicode_escape(self,s):
    return ''.join('\\u%04X' % ord(c) if ord(c) <= 0xFFFF else '\\u%08X' % ord(c) for c in s)
