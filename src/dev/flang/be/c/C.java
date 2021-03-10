/*

This file is part of the Fuzion language implementation.

The Fuzion language implementation is free software: you can redistribute it
and/or modify it under the terms of the GNU General Public License as published
by the Free Software Foundation, version 3 of the License.

The Fuzion language implementation is distributed in the hope that it will be
useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public
License for more details.

You should have received a copy of the GNU General Public License along with The
Fuzion language implementation.  If not, see <https://www.gnu.org/licenses/>.

*/

/*-----------------------------------------------------------------------
 *
 * Tokiwa GmbH, Berlin
 *
 * Source of class C
 *
 *---------------------------------------------------------------------*/

package dev.flang.be.c;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import java.util.Stack;
import java.util.TreeSet;

import java.util.stream.Stream;

import dev.flang.ir.Backend;
import dev.flang.ir.BackendCallable;
import dev.flang.ir.Clazz;   // NYI: remove this dependency!
import dev.flang.ir.Clazzes; // NYI: remove this dependency!

import dev.flang.fuir.FUIR;

import dev.flang.util.Errors;
import dev.flang.util.FuzionConstants;
import dev.flang.util.List;


/**
 * C provides a C code backend converting FUIR data into C code.
 *
 * @author Fridtjof Siebert (siebert@tokiwa.eu)
 */
public class C extends Backend
{


  /*----------------------------  constants  ----------------------------*/


  /**
   * C code generation phase for generating C functions for features.
   */
  private enum CompilePhase
  {
    TYPES           { void compile(C c, int cl) { c.types(cl);    } }, // declare types
    STRUCTS         { void compile(C c, int cl) { c.structs(cl);  } }, // generate struct declarations
    FORWARDS        { void compile(C c, int cl) { c.forwards(cl); } }, // generate forward declarations only
    IMPLEMENTATIONS { void compile(C c, int cl) { c.code(cl);     } }; // generate C functions

    /**
     * Perform this compilation phase on given clazz using given backend.
     *
     * @param c the backend
     *
     * @param cl the clazz.
     */
    abstract void compile(C c, int cl);
  }


  /**
   * Debugging output
   */
  private static final boolean SHOW_STACK_AFTER_STMNT = false;
  private static final boolean SHOW_STACK_ON_CALL = false;


  /*----------------------------  variables  ----------------------------*/


  /**
   * The intermediate code we are compiling.
   */
  final FUIR _fuir;


  /**
   * The options set for the compilation.
   */
  final COptions _options;


  /**
   * Writer to create the C code to.
   */
  private CFile _c;


  /**
   * Set of clazz ids for all the clazzes whose structs have been declared
   * already.  Structs are declared recursively with structs of inner fields
   * declared before outer structs.  This set keeps track which structs have been
   * declared already to avoid duplicates.
   */
  private final TreeSet<Integer> _declaredStructs = new TreeSet<>();


  /**
   * C identifier handling goes through _names:
   */
  final CNames _names;


  /*---------------------------  consructors  ---------------------------*/


  /**
   * Create C code backend for given intermidiate code.
   *
   * @param opt options to control compilation.
   *
   * @param fuir the intermeidate code.
   */
  public C(COptions opt,
           FUIR fuir)
  {
    _options = opt;
    _fuir = fuir;
    _names = new CNames(fuir);
    Clazzes.findAllClasses(this, _fuir.main()); /* NYI: remove this, should be done within FUIR */
    Errors.showAndExit();
  }


  /*-----------------------------  methods  -----------------------------*/


  /**
   * Obtain backend information required for dynamic binding lookup to perform a
   * call.
   *
   * @param dynamic true if this sets the static inner / outer clazz of a
   * dynamic call, false if this is a static call
   *
   * @param innerClazz the frame clazz of the called feature
   *
   * @param outerClazz the static clazz of the target instance of this call
   *
   * @return a beckend-specific object.
   */
  public BackendCallable callable(boolean dynamic,
                                  Clazz innerClazz,
                                  Clazz outerClazz)
  {
    return new BackendCallable()
      {
        public Clazz inner() { return innerClazz; }
        public Clazz outer() { return outerClazz; }
      };
  }


  /**
   * Create the C code from the intermediate code.
   */
  public void compile()
  {
    var cl = _fuir.mainClazzId();
    var name = _options._binaryName != null ? _options._binaryName : _fuir.clazzBaseName(cl);
    var cname = name + ".c";
    if (_options.verbose(1))
      {
        System.out.println(" + " + cname);
      }
    try
      {
        _c = new CFile(cname);
        createCode();
      }
    catch (IOException io)
      {
        Errors.error("C backend I/O error",
                     "While creating code to '" + cname + "', received I/O error '" + io + "'");
      }
    finally
      {
        if (_c != null)
          {
            _c.close();
            _c = null;
          }
      }
    Errors.showAndExit();

    var command = new List<String>("clang", "-O3", "-o", name, cname);
    if (_options.verbose(1))
      {
        System.out.println(" * " + command.toString("", " ", ""));
      }
    try
      {
        var p = new ProcessBuilder().inheritIO().command(command).start();
        p.waitFor();
        if (p.exitValue() != 0)
          {
            Errors.error("C backend: C compiler failed",
                         "C compiler call '" + command.toString("", " ", "") + "' failed with exit code '" + p.exitValue() + "'");
          }
      }
    catch (IOException | InterruptedException io)
      {
        Errors.error("C backend I/O error when running C Compiler",
                     "C compiler call '" + command.toString("", " ", "") + "'  received '" + io + "'");
      }
    Errors.showAndExit();
  }


  /**
   * After the CFile has been opened and stored in _c, this methods generates
   * the code into this file.
   */
  private void createCode()
  {
    _c.println
      ("#include <stdlib.h>\n"+
       "#include <stdio.h>\n"+
       "#include <unistd.h>\n"+
       "#include <stdint.h>\n"+
       "#include <assert.h>\n"+
       "\n");

    Stream.of(CompilePhase.values()).forEachOrdered
      ((p) ->
       {
         for (var c = _fuir.firstClazz(); c <= _fuir.lastClazz(); c++)
           {
             p.compile(this, c);
           }
       });
    _c.println("int main(int argc, char **args) { " + _names.function(_fuir.mainClazzId()) + "(); }");
  }


  /**
   * The type of a value of the given clazz.
   */
  String clazzTypeName(int cl)
  {
    return _names.struct(cl) + (_fuir.clazzIsRef(cl) ? "*" : "");
  }


  /**
   * The type of a field.  This is the usually the same as clazzTypeName() of
   * the field's result clazz, except for outer refs for which
   * clazzFieldIsAdrOfValue, where it is a pointer to that type.
   */
  String clazzFieldType(int cf)
  {
    var rc = _fuir.clazzResultClazz(cf);
    return (_fuir.clazzIsVoidType(rc)
            ? "struct { }"
            : clazzTypeName(rc)) + (_fuir.clazzFieldIsAdrOfValue(cf) ? "*" : "");
  }



  /**
   * Create declarations of the C types required for the given clazz.  Write
   * code to _c.
   *
   * @param cl a clazz id.
   */
  public void types(int cl)
  {
    switch (_fuir.clazzKind(cl))
      {
      case Choice:
      case Routine:
        {
          var name = _names.struct(cl);
          // special handling of stdlib clazzes known to the compiler
          var stype = scalarType(cl);
          var type = stype != null ? stype : "struct " + name;
          _c.print
                ("typedef " + type + " " + name + ";\n");
          break;
        }
      default:
        break;
      }
  }


  /**
   * Does the given clazz specify a scalar type in the C code, i.e, standard
   * numeric types i32, u64, etc.
   */
  boolean isScalarType(int cl)
  {
    return scalarType(cl) != null;
  }


  /**
   * Check if the given clazz specifies a scalar type in the C code, i.e,
   * standard numeric types i32, u64, etc. If so, return that C type.
   *
   * @return the C scalar type corresponding to cl, null if cl is not scaler.
   */
  String scalarType(int cl)
  {
    return
      _fuir.clazzIsI32(cl) ? "int32_t" :
      _fuir.clazzIsI64(cl) ? "int64_t" :
      _fuir.clazzIsU32(cl) ? "uint32_t" :
      _fuir.clazzIsU64(cl) ? "uint64_t" : null;
  }


  /**
   * Create declarations of the C structs required for the given clazz.  Write
   * code to _c.
   *
   * @param cl a clazz id.
   */
  public void structs(int cl)
  {
    switch (_fuir.clazzKind(cl))
      {
      case Choice:
      case Routine:
        {
          if (!_declaredStructs.contains(cl))
            {
              _declaredStructs.add(cl);
              if (!isScalarType(cl)) // special handling of stdlib clazzes known to the compiler
                {
                  // first, make sure structs used for inner fields are declared:
                  for (int i = 0; i < _fuir.clazzNumFields(cl); i++)
                    {
                      var cf = _fuir.clazzField(cl, i);
                      var rcl = _fuir.clazzResultClazz(cf);
                      if (!_fuir.clazzIsRef(rcl))
                        {
                          structs(rcl);
                        }
                    }
                  if (_fuir.clazzIsRef(cl))
                    {
                      structs(_fuir.clazzAsValue(cl));
                    }

                  // next, declare the struct itself
                  _c.print
                    ("// for " + _fuir.clazzAsString(cl) + "\n" +
                     "struct " + _names.struct(cl) + " {\n");
                  if (_fuir.clazzIsChoice(cl))
                    {
                      var ct = _fuir.clazzChoiceTag(cl);
                      if (ct != -1)
                        {
                          String type = clazzFieldType(ct);
                          _c.print(" " + type + " " + _names.TAG_NAME + ";\n");
                        }
                      _c.print(" union {\n");
                      for (int i = 0; i < _fuir.clazzNumChoices(cl); i++)
                        {
                          var cc = _fuir.clazzChoice(cl, i);
                          if (!_fuir.clazzIsRef(cc))
                            {
                              String type = clazzTypeName(cc);
                              _c.print("  " + type + " " + _names.CHOICE_ENTRY_NAME + i + ";\n");
                            }
                        }
                      if (_fuir.clazzIsChoiceWithRefs(cl))
                        {
                          _c.print("  " + _names.struct(_fuir.clazzObject()) + " " + _names.CHOICE_REF_ENTRY_NAME + ";\n");
                        }
                      _c.print(" } " + _names.CHOICE_UNION_NAME + ";\n");
                    }
                  else if (_fuir.clazzIsRef(cl))
                    {
                      var vcl = _fuir.clazzAsValue(cl);
                      _c.print("  uint32_t clazzId;\n" +
                               "  " + clazzTypeName(vcl) + " " + _names.FIELDS_IN_REF_CLAZZ + ";\n");
                    }
                  else
                    {
                      for (int i = 0; i < _fuir.clazzNumFields(cl); i++)
                        {
                          var cf = _fuir.clazzField(cl, i);
                          String type = clazzFieldType(cf);
                          _c.print(" " + type + " " + _names.fieldName(i, cf) + ";\n");
                        }
                    }
                  _c.print
                    ("};\n\n");
                }
            }
        }
        break;
      default:
        break;
      }
  }


  /**
   * Test is a given clazz is not -1 and stores data.
   *
   * @param cl the clazz of val, may be -1
   *
   * @return true if cl != -1 and not unit or void type.
   */
  boolean hasData(int cl)
  {
    return cl != -1 &&
      !_fuir.clazzIsUnitType(cl) &&
      !_fuir.clazzIsVoidType(cl);
  }


  /**
   * Push the given value to the stack unless it is of unit or void type or the
   * clazz is -1
   *
   * @param stack the stack to push val to
   *
   * @param cl the clazz of val, may be -1
   *
   * @param the value to push
   */
  void push(Stack<CExpr> stack, int cl, CExpr val)
  {
    if (PRECONDITIONS) require
      (hasData(cl) || val != null);

    if (hasData(cl))
      {
        stack.push(val);
      }
  }


  /**
   * Pop value from the stack unless it is of unit or void type or the
   * clazz is -1
   *
   * @param stack the stack to pop value from
   *
   * @param cl the clazz of value, may be -1
   *
   * @return the popped value or null if cl is -1 or unit type
   */
  CExpr pop(Stack<CExpr> stack, int cl)
  {
    return hasData(cl) ? stack.pop()
                       : null;
  }


  /**
   * Create C code for code block c of clazz cl with given stack contents at
   * beginning of the block.  Write code to _c.
   *
   * @param cl clazz id
   *
   * @param stack the stack containing the current arguments waiting to be used
   *
   * @param c the code block to compile
   */
  void createCode(int cl, Stack<CExpr> stack, int c)
  {
    for (int i = 0; _fuir.withinCode(c, i); i++)
      {
        var s = _fuir.codeAt(c, i);
        _c.print(CStmnt.lineComment("Code for statement " + s));
        CStmnt o = CStmnt.EMPTY;
        switch (s)
          {
          case AdrToValue:
            { // dereference an outer reference
              if (false) // NYI: Check what AdrToValue is applied to empty stack and why it can be a NOP for the C backend
                {
                  var a = stack.pop();
                  var v = a;  /* a.deref(); --  NYI: AdrToValue is NOP for now since outer refs as values not supported in C backend yet */
                  stack.push(v);
                }
              break;
            }
          case Assign:
            {
              var field = _fuir.assignedField(cl, c, i);  // field we are assigning to
              if (field != -1)
                {
                  var outercl   = _fuir.assignOuterClazz(cl, c, i);  // static clazz of outer
                  var valuecl   = _fuir.assignValueClazz(cl, c, i);  // static clazz of value
                  var fclazz    = _fuir.clazzResultClazz(field);     // static clazz of assigned field
                  var voutercl  = _fuir.clazzAsValue(outercl);
                  var fieldName = _names.fieldNameInClazz(voutercl, field);
                  var outer     = pop(stack, outercl);                 // instance containing assigned field
                  if (_fuir.clazzIsChoice(fclazz) &&
                      fclazz != valuecl  // NYI: interpreter checks fclazz._type != staticTypeOfValue
                      )
                    {
                      check
                        (!_fuir.clazzIsOuterRef(field)); /* the interprer checks for this separately, just ot be sure we do it as well */

                      var value = pop(stack, valuecl);                // value assigned to field
                      int tagNum = _fuir.clazzChoiceTag(fclazz, valuecl);
                      var f = ccodeAccessField(outercl, outer, fieldName);
                      var tag = f.field(_names.TAG_NAME);
                      var uniyon = f.field(_names.CHOICE_UNION_NAME);
                      var entry = uniyon.field(_fuir.clazzIsRef(valuecl) ? _names.CHOICE_REF_ENTRY_NAME
                                                                         : _names.CHOICE_ENTRY_NAME + tagNum);
                      if (_fuir.clazzIsChoiceOfOnlyRefs(fclazz) && !_fuir.clazzIsRef(valuecl))
                        { // replace unit-type values by 0 or an odd value cast to ref Object
                          check
                            (value == null); // value must be a unit type
                          value = CExpr.int32const(tagNum == 0 ? 0 : tagNum*2 - 1).castTo(_names.struct(_fuir.clazzObject()) + "*");
                        }
                      o = CStmnt.seq(CStmnt.lineComment("Assign to choice field type " + _fuir.clazzAsString(fclazz) + " static value type " + _fuir.clazzAsString(valuecl)),
                                     _fuir.clazzIsChoiceOfOnlyRefs(fclazz) ? CStmnt.EMPTY : tag.assign(CExpr.int32const(tagNum)),
                                     value == null
                                     ? CStmnt.lineComment("valueluess assignment to " + entry.code())
                                     : entry.assign(value));
                    }
                  else
                    {
                      var value = pop(stack, fclazz);                // value assigned to field
                      if (_fuir.clazzIsRef(fclazz))
                        {
                          value = value.castTo(clazzTypeName(fclazz));
                        }
                      // _c.print("// Assign to "+_fuir.clazzAsString(fclazz)+" outercl "+_fuir.clazzAsString(outercl)+" valuecl "+_fuir.clazzAsString(valuecl));
                      o = value == null
                        ? CStmnt.lineComment("valueluess assignment to " + outer)
                        : ccodeAccessField(outercl, outer, fieldName).assign(value);
                    }
                }
              break;
            }
          case Box:
            {
              var vc = _fuir.boxValueClazz(cl, c, i);
              var rc = _fuir.boxResultClazz(cl, c, i);
              if (_fuir.clazzIsRef(vc))
                { // vc's type is a generic argument whose actual type does not need
                  // boxing
                  o = CStmnt.lineComment("Box " + _fuir.clazzAsString(vc) + " is NOP, clazz is already a ref");
                }
              else
                {
                  if (_fuir.clazzIsChoice(vc))
                    {
                      _c.println("// NYI: choice boxing");
                      _c.print(CExpr.call("assert", new List<>(CExpr.int32const(0))).comment("choice boxing"));
                  /* NYI choice boxing:

                check
                  (rc.isChoice());
                if (vc.isChoiceOfOnlyRefs())
                  {
                    throw new Error("NYI: Boxing choiceOfOnlyRefs, does this happen at all?");
                  }
                else
                  {
                    check
                      (!rc.isChoiceOfOnlyRefs());

                    var voff = vc.choiceValsOffset_;
                    var roff = rc.choiceValsOffset_;
                    var vsz = vc.choiceValsSize_;
                    check
                      (rc.choiceValsSize_ == vsz);
                    if (val instanceof LValue)
                      {
                        voff += ((LValue) val).offset;
                        val   = ((LValue) val).container;
                      }
                    if (val instanceof boolValue)
                      {
                        val.storeNonRef(new LValue(Clazzes.bool.get(), ri, roff), Clazzes.bool.get().size());
                      }
                    else
                      {
                        var vi = (Instance) val;
                        for (int i = 0; i<vsz; i++)
                          {
                            ri.refs   [roff+i] = vi.refs   [voff+i];
                            ri.nonrefs[roff+i] = vi.nonrefs[voff+i];
                          }
                      }
                  }
                  */
                      push(stack, rc, _names.CDUMMY);
                    }
                  else
                    {
                      var val = pop(stack, vc);
                      var t = new CIdent(_names.newTemp());
                      o = CStmnt.seq(CStmnt.lineComment("Box " + _fuir.clazzAsString(vc)),
                                     CStmnt.decl(clazzTypeName(rc), t),
                                     t.assign(CExpr.call("malloc", new List<>(new CIdent(_names.struct(rc)).sizeOfType()))),
                                     t.deref().field("clazzId").assign(_names.clazzId(rc)),
                                     val == null ? CStmnt.EMPTY
                                     : t.deref().field(_names.FIELDS_IN_REF_CLAZZ).assign(val));
                      push(stack, rc, t);
                    }
                }
              break;
            }
          case Call:
            {
              if (_fuir.callIsDynamic(cl, c, i))
                {
                  var cc0 = _fuir.callCalledClazz  (cl, c, i);
                  _c.println("// Dynamic call to " + _fuir.clazzAsString(cc0));
                  var ccs = _fuir.callCalledClazzes(cl, c, i);
                  var ac = _fuir.callArgCount(c, i);
                  var tc = _fuir.callTargetClazz(cl, c, i);
                  var t = new CIdent(_names.newTemp());
                  var ti = stack.size() - ac - 1;
                  var tt0 = clazzTypeName(tc);
                  _c.println(tt0 + " " + t.code()+ ";");
                  _c.print(t.assign(stack.get(ti).castTo(tt0)));
                  stack.set(ti, t);
                  var id = t.deref().field("clazzId");
                  if (ccs.length == 2)
                    {
                      var tt = ccs[0];
                      var cc = ccs[1];
                      _c.println("// Dynamic call to " + _fuir.clazzAsString(cc0) + " with exactly one target");
                      _c.print(CExpr.call("assert",new List<>(CExpr.eq(id, _names.clazzId(tt))))); // <-- perfect reason to make () optional
                      _c.print(call(cl, c, i, cc, stack, _fuir.clazzOuterClazz(cc)));
                    }
                  else if (ccs.length == 0)
                    {
                      _c.println("fprintf(stderr,\"*** no possible call target found\\n\"); exit(1);");
                    }
                  else
                    {
                      CExpr res = null;
                      var rt = _fuir.clazzResultClazz(cc0);
                      if (hasData(rt) &&
                          (!_fuir.withinCode(c, i+1) || _fuir.codeAt(c, i+1) != FUIR.ExprKind.WipeStack))
                        {
                          res = new CIdent(_names.newTemp());
                          _c.println(clazzTypeName(rt) + " " + res.code() + ";");
                        }
                      _c.println("switch (" + id.code() + ") {");
                      _c.indent();
                      var stack2 = stack;
                      for (var cci = 0; cci < ccs.length; cci += 2)
                        {
                          var tt = ccs[cci  ];
                          var cc = ccs[cci+1];
                          stack =  (Stack<CExpr>) stack2.clone();
                          _c.println("// Call calls "+ _fuir.clazzAsString(cc) + " target: " + _fuir.clazzAsString(tt) + ":");
                          _c.println("case " + _names.clazzId(tt).code() + ": {");
                          _c.indent();
                          _c.print(call(cl, c, i, cc, stack, _fuir.clazzOuterClazz(cc)));
                          var rt2 = _fuir.clazzResultClazz(cc); // NYI: Check why rt2 and rt can be different
                          if (hasData(rt2))
                            {
                              var rv = pop(stack, rt2);
                              if ((rt == rt2 || _fuir.clazzIsRef(rt) && _fuir.clazzIsRef(rt2)) && // NYI: Remove this conditions when ccs set no longer contains false entries
                                  rv != _names.CDUMMY)
                                {
                                  if (res != null)
                                    {
                                      if (_fuir.clazzIsRef(rt))
                                        {
                                          rv = rv.castTo(clazzTypeName(rt));
                                        }
                                      _c.print(res.assign(rv));
                                    }
                                }
                            }
                          _c.print(CStmnt.BREAK);
                          _c.unindent();
                          _c.println("}");
                        }
                      _c.println("default: { fprintf(stderr,\"*** %s:%d unhandled dynamic call target %d in call to "+_fuir.clazzAsString(cc0)+" within "+_fuir.clazzAsString(cl)+"\\n\", __FILE__, __LINE__, " + id.code() + "); exit(1); }");
                      _c.unindent();
                      _c.println("}");
                      stack = stack2;
                      args(cl, c, i, cc0, stack, _fuir.clazzArgCount(cc0), _fuir.clazzOuterClazz(cc0));
                      if (res != null)
                        {
                          push(stack, rt, res);
                        }
                    }
                }
              else
                {
                  var cc = _fuir.callCalledClazz(cl, c, i);
                  _c.print(call(cl, c, i, cc, stack, -1));
                }
              break;
            }
          case Current:
            {
              push(stack, cl, current(cl));
              break;
            }
          case If:
            {
              var cond = stack.pop();
              var block     = _fuir.i32Const(c, i + 1);
              var elseBlock = _fuir.i32Const(c, i + 2);
              var stack2 = stack;
              i = i + 2;
              _c.println("if (" + cond.field(_names.TAG_NAME).code() + " != 0) {");
              _c.indent();
              stack = (Stack<CExpr>) stack2.clone();
              createCode(cl, stack, block);
              _c.unindent();
              _c.println("} else {");
              _c.indent();
              stack = stack2;
              createCode(cl, stack, elseBlock);
              _c.unindent();
              _c.println("}");
              break;
            }
          case boolConst:
            {
              var bc = _fuir.boolConst(c, i);
              stack.push(bc ? _names.FZ_TRUE
                            : _names.FZ_FALSE);
              break;
            }
          case i32Const: { var ic = _fuir.i32Const(c, i); stack.push(CExpr. int32const(ic)); break; }
          case u32Const: { var ic = _fuir.u32Const(c, i); stack.push(CExpr.uint32const(ic)); break; }
          case i64Const: { var ic = _fuir.i64Const(c, i); stack.push(CExpr. int64const(ic)); break; }
          case u64Const: { var ic = _fuir.u64Const(c, i); stack.push(CExpr.uint64const(ic)); break; }
          case strConst:
            {
              var bytes = _fuir.strConst(c, i);
              var tmp = _names.newTemp();
              o = constString(bytes, tmp);
              stack.push(new CIdent(tmp));
              break;
            }
          case Match:
            {
              var staticSubjectClazz = _fuir.matchStaticSubject(cl, c, i);
              var sub = pop(stack, staticSubjectClazz);
              if (_fuir.clazzIsChoiceOfOnlyRefs(staticSubjectClazz))
                {
                  o = CStmnt.seq(CStmnt.lineComment("NYI: match of only refs!"),
                                 CExpr.call("assert", new List<>(CExpr.int32const(0))).comment("match of only refs!"));
              /* from Interpreter:

                refVal = getChoiceRefVal(sf, staticSubjectClazz, sub);
                tag = ChoiceIdAsRef.get(staticSubjectClazz, refVal);
              */
                }
              else
                {
                  var tag = sub.field(_names.TAG_NAME);
                  _c.println("switch ("+tag.code()+") {");
                  _c.indent();
                  var stack2 = stack;
                  var mcc = _fuir.matchCaseCount(c, i);
                  for (var mc = 0; mc < mcc; mc++)
                    {
                      var block = _fuir.i32Const(c, i + 1 + mc);
                      var field = _fuir.matchCaseField(cl, c, i, mc);
                      var tags  = _fuir.matchCaseTags(cl, c, i, mc);
                      if (tags.length > 0)
                        {
                          for (var tagNum : tags)
                            {
                              _c.print("case " + tagNum + ": ");
                            }
                          if (field != -1)
                            {
                              check
                                (tags.length == 1);
                              var fclazz    = _fuir.clazzResultClazz(field);     // static clazz of assigned field
                              var vcl       = _fuir.clazzAsValue(cl);
                              var fieldName = _names.fieldNameInClazz(vcl, field);
                              var f         = ccodeAccessField(cl, current(cl), fieldName);
                              var uniyon    = sub.field(_names.CHOICE_UNION_NAME);
                              var entry     = _fuir.clazzIsRef(fclazz) ? uniyon.field(_names.CHOICE_REF_ENTRY_NAME).castTo(clazzTypeName(fclazz))
                                                                       : uniyon.field(_names.CHOICE_ENTRY_NAME + tags[0]);
                              _c.print(!hasData(fclazz) ? CStmnt.lineComment("valueluess assignment to " + f.code())
                                                        : f.assign(entry));
                            }
                          _c.println("{");
                          _c.indent();
                          stack = (Stack<CExpr>) stack2.clone();
                          createCode(cl, stack, block);
                          _c.println("break;");
                          _c.unindent();
                          _c.println("}");
                        }
                    }
                  _c.unindent();
                  _c.println("}");
                  i = i + mcc;
                }
              break;
            }
          case Singleton:
            {
              o = CStmnt.lineComment("NYI: singleton");
              break;
            }
          case WipeStack:
            {
              stack.clear();
              break;
            }
          case NOP:
            {
              break;
            }
          default:
            {
              System.err.println("*** error: C backend does not handle statments of type " + s);
            }
          }
        _c.print(o);
        if (SHOW_STACK_AFTER_STMNT) System.out.println("After " + s +" in "+_fuir.clazzAsString(cl)+": "+stack);
      }
  }


  /**
   * Create code to create a constant string and assign it to a new temp
   * variable. Return an CExpr that reads this variable.
   */
  CStmnt constString(byte[] bytes, String tmp)
  {
    StringBuilder sb = new StringBuilder();
    for (var bb : bytes)
      {
        var b = bb & 0xff;
        sb.append("\\"+((b >> 6) & 7)+((b >> 3) & 7)+(b & 7));
        sb.append("...");
      }
    var t = new CIdent(tmp);
    return CStmnt.seq(CStmnt.decl("fzT__Rconststring *", t),
                      new CIdent(tmp).assign(CExpr.call("malloc", new List<>(new CIdent("fzT__Rconststring").sizeOfType()))),
                      t.deref().field("clazzId").assign(_names.clazzId(_fuir.clazz_conststring())),
                      t.deref().field(_names.FIELDS_IN_REF_CLAZZ).field("fzF_1_data").assign(CExpr.string(sb.toString()).castTo("void *")),
                      t.deref().field(_names.FIELDS_IN_REF_CLAZZ).field("fzF_3_length").assign(CExpr.int32const(bytes.length)));
  }


  /**
   * Create C code for a statically bound call.
   *
   * @param cl clazz id of the currently compiled clazz
   *
   * @param c the code block currently compiled
   *
   * @param i index in c of the current call
   *
   * @param cc clazz that is called
   *
   * @param stack the stack containing the current arguments waiting to be used
   *
   * @param castTarget if the type of the target instance of this call was
   * checked against a different type, the target type should be cast to this
   * clazz castTarget. -1 if no cast needed.
   *
   * @return the code to perform the call
   */
  CStmnt call(int cl, int c, int i, int cc, Stack<CExpr> stack, int castTarget)
  {
    CStmnt result = CStmnt.EMPTY;
    var ac = _fuir.callArgCount(c, i);
    var rt = _fuir.clazzResultClazz(cc);
    if (ac != _fuir.clazzArgCount(cc)) // NYI: Remove this conditions when ccs set no longer contains false entries
      {
        _c.println("// Arg count does not match, expected "+ac+", called clazz "+_fuir.clazzAsString(cc)+" has "+_fuir.clazzArgCount(cc));
        push(stack, rt, _names.CDUMMY);
      }
    else
    switch (_fuir.clazzKind(cc))
      {
      case Routine  :
      case Intrinsic:
        {
          if (SHOW_STACK_ON_CALL) System.out.println("Before call to "+_fuir.clazzAsString(cc)+": "+stack);
          CExpr res = null;
          var call = CExpr.call(_names.function(cc), args(cl, c, i, cc, stack, ac, castTarget));
          if (hasData(rt))
            {
              var tmp = new CIdent(_names.newTemp());
              res = tmp;
              result = CStmnt.seq(CStmnt.decl(clazzTypeName(rt), tmp),
                                  res.assign(call));
              push(stack, rt, res);
            }
          else
            {
              result = call;
            }
          if (SHOW_STACK_ON_CALL) System.out.println("After call to "+_fuir.clazzAsString(cc)+": "+stack);
          break;
        }
      case Field:
        {
          var tc = _fuir.callTargetClazz(cl, c, i);
          var t = pop(stack, tc);
          check
            (t != null || !hasData(rt));
          var vtc = _fuir.clazzAsValue(tc);
          var field = _names.fieldName(_fuir.callFieldOffset(vtc, c, i), cc);
          CExpr res = isScalarType(vtc) ? _fuir.clazzIsRef(tc) ? t.deref().field("fields") : t :
                      t != null         ? ccodeAccessField(tc, t, field) : null;
          res = _fuir.clazzFieldIsAdrOfValue(cc) ? res.deref() : res;
          push(stack, rt, res);
          break;
        }
      case Abstract: throw new Error("This should not happen: Calling abstract '" + _fuir.clazzAsString(cc) + "'");
      default:       throw new Error("This should not happen: Unknown feature kind: " + _fuir.clazzKind(cc));
      }
    return result;
  }


  /**
   * Create C code to pass given number of arguments plus one implicit target
   * argument from the stack to a called feature.
   *
   * @param cl clazz id of the currently compiled clazz
   *
   * @param c the code block currently compiled
   *
   * @param i index in c of the current call
   *
   * @param cc clazz that is called
   *
   * @param stack the stack containing the C code of the args.
   *
   * @param argCount the number of arguments.
   *
   * @return list of arguments to be passed to CExpr.call
   */
  List<CExpr> args(int cl, int c, int i, int cc, Stack<CExpr> stack, int argCount, int castTarget)
  {
    List<CExpr> result;
    if (argCount > 0)
      {
        var ac = _fuir.clazzArgClazz(cc, argCount-1);
        var a = pop(stack, ac);
        result = args(cl, c, i, cc, stack, argCount-1, castTarget);
        if (hasData(ac))
          {
            a = _fuir.clazzIsRef(ac) ? a.castTo(clazzTypeName(ac)) : a;
            result.add(a);
          }
      }
    else // NYI: special handling of outer refs should not be part of BE, should be moved to FUIR
      { // ref to outer instance, passed by reference
        result = new List<>();
        var tc = _fuir.callTargetClazz(cl, c, i);
        var or = _fuir.clazzOuterRef(cc);
        var a = pop(stack, tc);
        if (or != -1)
          {
            var a2 = _fuir.clazzFieldIsAdrOfValue(or) ? a.adrOf() : a;
            var a3 = castTarget == -1 ? a2 : a2.castTo(clazzTypeName(castTarget));
            result.add(a3);
          }
      }
    return result;
  }


  /**
   * Create code for the C function implemeting the routine corresponding to the
   * given clazz.  Write code to _c.
   *
   * @param cl id of clazz to compile
   */
  private void cFunctionDecl(int cl)
  {
    var res = _fuir.clazzResultClazz(cl);
    _c.print(!hasData(res)
             ? "void "
             : clazzTypeName(res) + " ");
    _c.print(_names.function(cl));
    _c.print("(");
    String comma = "";
    var or = _fuir.clazzOuterRef(cl);
    if (or != -1)
      {
        _c.print(clazzFieldType(or));
        _c.print(" fzouter");
        comma = ", ";
      }
    var ac = _fuir.clazzArgCount(cl);
    for (int i = 0; i < ac; i++)
      {
        var at = _fuir.clazzArgClazz(cl, i);
        if (hasData(at))
          {
            _c.print(comma);
            var t = clazzTypeName(at);
            _c.print(t + " arg" + i);
            comma = ", ";
          }
      }
    _c.print(")");
  }


  /**
   * Create forward declarations for given clazz cl.
   *
   * @param cl id of clazz to compile
   */
  public void forwards(int cl)
  {
    switch (_fuir.clazzKind(cl))
      {
      case Routine:
      case Intrinsic:
        {
          cFunctionDecl(cl);
          _c.print(";\n");
          break;
        }
      }
  }


  /**
   * Create code for given clazz cl.
   *
   * @param cl id of clazz to compile
   */
  public void code(int cl)
  {
    _names._tempVarId = 0;  // reset counter for unique temp variables for function results
    switch (_fuir.clazzKind(cl))
      {
      case Routine:
        {
          _c.print("\n// code for clazz#"+_names.clazzId(cl).code()+" "+_fuir.clazzAsString(cl)+":\n");
          cFunctionDecl(cl);
          _c.print(" {\n");
          _c.indent();
          codeForRoutine(cl);
          _c.unindent();
          _c.println("}");
          break;
        }
      case Intrinsic:
        {
          _c.print("\n// code for intrinsic " + _fuir.clazzAsString(cl) + ":\n");
          cFunctionDecl(cl);
          _c.print(" {\n");
          _c.indent();
          _c.print(new Intrinsics().code(this, cl));
          _c.unindent();
          _c.print("}\n");
        }
      }
  }


  /**
   * Create code for given clazz cl of type Routine.
   *
   * @param cl id of clazz to generate code for
   */
  void codeForRoutine(int cl)
  {
    if (PRECONDITIONS) require
      (_fuir.clazzKind(cl) == FUIR.ClazzKind.Routine);

    _c.print("" + _names.struct(cl) + " *" + _names.CURRENT.code() + " = malloc(sizeof(" + _names.struct(cl) + "));\n"+
             (_fuir.clazzIsRef(cl) ? _names.CURRENT.deref().field("clazzId").assign(_names.clazzId(cl)).code() + ";\n" : ""));

    var cur = _fuir.clazzIsRef(cl) ? _names.CURRENT.deref().field(_names.FIELDS_IN_REF_CLAZZ)
                                   : _names.CURRENT.deref();
    var vcl = _fuir.clazzAsValue(cl);
    var or = _fuir.clazzOuterRef(vcl);
    if (or != -1)
      {
        _c.print(cur.field(_names.fieldNameInClazz(vcl, or)).assign(_names.OUTER));
      }

    var ac = _fuir.clazzArgCount(vcl);
    for (int i = 0; i < ac; i++)
      {
        var af = _fuir.clazzArg(vcl, i);
        var at = _fuir.clazzArgClazz(vcl, i);
        if (hasData(at))
          {
            var target = isScalarType(vcl)
              ? cur
              : cur.field(_names.fieldNameInClazz(vcl, af));
            _c.print(target.assign(new CIdent("arg" + i)));
          }
      }
    var c = _fuir.clazzCode(cl);
    var stack = new Stack<CExpr>();
    try
      {
        createCode(cl, stack, c);
      }
    catch (RuntimeException | Error e)
      {
        _c.println("// *** compiler crash: " + e);
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        Errors.error("C backend compiler crash",
                     "While creating code for " + _fuir.clazzAsString(cl) + "\n" +
                     "Java Error: " + sw);
      }
    var res = _fuir.clazzResultClazz(cl);
    if (hasData(res))
      {
        var rf = _fuir.clazzResultField(cl);
        _c.print(rf != -1
                 ? current(cl).field(_names.fieldNameInClazz(cl, rf)).ret()  // a routine, return result field
                 : current(cl).ret()                                         // a constructor, return current instance
                 );
      }
  }


  /**
   * Return the current instance of the currently compiled clazz cl. This is a C
   * pointer in case _fuir.clazzIsRef(cl), or the C struct corresponding to cl
   * otherwise.
   */
  CExpr current(int cl)
  {
    return _fuir.clazzIsRef(cl)
      ? _names.CURRENT
      : _names.CURRENT.deref();
  }


  /**
   * Create C code to access a field, dereferencing if needed.
   *
   * @param outercl the clazz id of the type of outer, used to tell if outer is ref or value
   *
   * @param outer C expression that result in the instance that contains the field
   *
   * @param fieldName C identifier that gives the name of the field
   */
  CExpr ccodeAccessField(int outercl, CExpr outer, String fieldName)
  {
    if (_fuir.clazzIsRef(outercl))
      {
        outer = outer.deref().field(_names.FIELDS_IN_REF_CLAZZ);
      }
    return outer.field(fieldName);
  }

}

/* end of file */
