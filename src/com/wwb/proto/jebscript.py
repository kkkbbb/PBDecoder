from com.pnfsoftware.jeb.client.api import IScript
from com.pnfsoftware.jeb.core.units.code import ICodeUnit
from com.pnfsoftware.jeb.core.units.code.android import IDexUnit
from com.pnfsoftware.jeb.core.units.code.android.dex import IDexCodeItem
from com.pnfsoftware.jeb.client.api import FormEntry
from java.lang import System
import sys
sys.path.append(r"D:\tools\PBDecoder.jar")
System.setProperty("python.security.respectJavaAccessibility", "false")
from com.wwb.proto import PBMain

dexUnit = None
parsedClass = []
    
class test(IScript):
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
    #print currentproto
    
    cresultstr = "message " + cls.getName() + " {\n"
    subresult = ""
    for fields in currentproto.split("\n"):
      if not len(fields) > 0: continue
      if "{" in fields or "}" in fields:
        cresultstr +="\t"+fields+"\n"
        continue
      field = fields.split("=")[0].split(" ")
      mfieldType =field[1] if not "oneof" in field[1] else field[0]
      mfieldType = mfieldType.strip()
      if mfieldType == "message" or mfieldType == "group":
        for clsField in cls.getFields():
          if clsField.getName() == field[2]:
            mtype = clsField.getFieldType()
            cresultstr += "\t" + fields.replace(mfieldType,mtype.getName()) + "\n"
            if mtype.getName() in parsedClass: continue
            subresult += self.parseCls(mtype.getImplementingClass())
        continue
          
      if mfieldType == "enum":
        fields = fields.replace("enum","int32") + " //unknow enum"
      cresultstr +="\t"+fields+"\n"
      
      if not self.isBaseType(mfieldType):
        mfieldType = mfieldType.strip()
        if mfieldType in parsedClass: continue
        subresult += self.parseCls(dexUnit.getClass("L"+mfieldType+";"))
        
    return cresultstr+"}\n\n"+subresult
  
  def isBaseType(self,mtype):
    for basetype in ["enum","string","int","float","bool","fixed","bytes","oneof","map","group"]:
      if basetype in mtype:
        return True
    return False
    

  def parseProto(self,cls):
    for method in cls.getMethods():
      if method.getName() == "<init>" or method.getName() == "<clinit>": continue

      codeItem = method.getData().getCodeItem()
      
      objs = ""
      messageinfo = ""
      if isinstance(codeItem, IDexCodeItem):
        instructions = codeItem.getInstructions()
        for firststr,ins in enumerate(instructions):
          if ins.getMnemonic() == "const-string":
            break
        
        if firststr == len(instructions)-1: continue  #inccorect method!
        while True:
          ins = instructions[firststr]
          firststr+=1
          # print ins
          if ins.getMnemonic() == "const-string":
            conststr = dexUnit.getString(ins.getOperands()[1].getValue()).getValue()
            if len(conststr) > 2 or "\x01" in conststr or "\x02" in conststr:
              messageinfo = conststr
            else:
              objs+=conststr+","
            continue
          if ins.getMnemonic() == "const-class":
            objs+= dexUnit.getType(ins.getParameters()[1].getValue()).getName()+","
            continue
          if ins.getMnemonic() == "sget-object":
            objs+="enum.type,"
            continue
          if "invoke-static" == ins.getMnemonic() or firststr >= len(instructions)-1:
            break
        
        if len(messageinfo) < 2: continue
        else: break
            
    #print cls,method
    if len(messageinfo) < 2: raise Exception("Unexcept messageinfo!")
    if len(objs) < 1: return ""
    return PBMain.forJeb(self.to_unicode_escape(messageinfo),objs)
      
  def to_unicode_escape(self,s):
    return ''.join('\\u%04X' % ord(c) if ord(c) <= 0xFFFF else '\\u%08X' % ord(c) for c in s)
