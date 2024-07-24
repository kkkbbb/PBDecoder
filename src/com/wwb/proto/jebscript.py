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

class test(IScript):
    def run(self, ctx):
        global dexUnit
        prj = ctx.getMainProject()
        dexUnit = prj.findUnit(IDexUnit)

        className = ctx.displayForm("proto class","the class of protobuf",
                                    FormEntry.Text('className', '', FormEntry.INLINE, None, 0, 0))[0]
        protoresult = self.parseCls(dexUnit.getClass("L"+className+";"));
        ctx.displayText("proto", protoresult,False)

    def parseCls(self,cls):
        currentproto = self.parseProto(cls)

        cresultstr = "message " + cls.getName() + " {\n"
        subresult = ""
        for fields in currentproto.split("\n"):
            if not len(fields) > 1 or "{" in fields or "}" in fields: continue
            field = fields.split("=")[0].split(" ")
            mfieldType =field[1] if not "oneof" in field[1] else field[0]
            if mfieldType == "message":
                for clsField in cls.getFields():
                    if clsField.getName() == field[2]:
                        mtype = clsField.getFieldType()
                        cresultstr += "\t" + fields.replace("message",mtype.getName()) + "\n"
                        subresult += self.parseCls(mtype.getImplementingClass())
                continue

            cresultstr +="\t"+fields+"\n"

            if not self.isBaseType(mfieldType):
                #print mfieldType
                subresult += self.parseCls(dexUnit.getClass("L"+mfieldType.strip()+";"))

        return cresultstr+"}\n\n"+subresult

    def isBaseType(self,mtype):
        for basetype in ["enum","string","int","int","bool","fixed","bytes","oneof"]:
            if basetype in mtype:
                return True
        return False


    def parseProto(self,cls):
        for method in cls.getMethods():
            if method.getName() != "<init>" and method.getName() != "<clinit>":
                break
        codeItem = method.getData().getCodeItem()

        objs = ""
        if isinstance(codeItem, IDexCodeItem):
            instructions = codeItem.getInstructions()
            for firststr,ins in enumerate(instructions):
                if ins.getMnemonic() == "const-string":
                    break

            while True:
                ins = instructions[firststr]
                firststr+=1
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
                    objs+="enum,"
                    continue
                if "filled-new-array" in ins.getMnemonic() or "invoke-static" == ins.getMnemonic():
                    break
                else:
                    raise Exception("Unknow Instruction!")

            if len(objs) < 1: return ""
            #print messageinfo,objs
            return PBMain.forJeb(self.to_unicode_escape(messageinfo),objs)

    def to_unicode_escape(self,s):
        return ''.join('\\u%04X' % ord(c) if ord(c) <= 0xFFFF else '\\u%08X' % ord(c) for c in s)



