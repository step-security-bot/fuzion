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
 * Tokiwa Software GmbH, Germany
 *
 * Source of class Intrinsics
 *
 *---------------------------------------------------------------------*/

package dev.flang.be.interpreter;

import dev.flang.ast.Consts; // NYI: remove dependency! Use dev.flang.fuir instead.
import dev.flang.ast.Feature; // NYI: remove dependency! Use dev.flang.fuir instead.
import dev.flang.ast.Impl; // NYI: remove dependency! Use dev.flang.fuir instead.
import dev.flang.ast.Type; // NYI: remove dependency! Use dev.flang.fuir instead.
import dev.flang.ast.Types; // NYI: remove dependency! Use dev.flang.fuir instead.

import dev.flang.ir.Clazz;
import dev.flang.ir.Clazzes;

import dev.flang.util.ANY;
import dev.flang.util.Errors;
import dev.flang.util.List;

import java.nio.charset.StandardCharsets;


/**
 * Intrinsics provides the implementation of Fuzion's intrinsic features.
 *
 * @author Fridtjof Siebert (siebert@tokiwa.software)
 */
public class Intrinsics extends ANY
{

  /*----------------------------  constants  ----------------------------*/


  /**
   * NYI: This will eventually be part of a Fuzion IR / BE Config class.
   */
  public static Boolean ENABLE_UNSAFE_INTRINSICS = null;


  /*----------------------------  variables  ----------------------------*/


  /*-------------------------  static methods  --------------------------*/


  /**
   * Create a Callable to call an intrinsic feature.
   *
   * @param innerClazz the frame clazz of the called feature
   *
   * @return a Callable instance to execute the intrinsic call.
   */
  public static Callable call(Clazz innerClazz)
  {
    if (PRECONDITIONS) require
      (innerClazz.feature().impl == Impl.INTRINSIC);

    Callable result;
    var f = innerClazz.feature();
    String n = f.qualifiedName();
    // NYI: We must check the argument count in addition to the name!
    if (n.equals("fuzion.std.out.write"))
      {
        result = (args) ->
          {
            System.out.write(args.get(1).u8Value());
            return Value.EMPTY_VALUE;
          };
      }
    else if (n.equals("fuzion.std.out.flush"))
      {
        result = (args) ->
          {
            System.out.flush();
            return Value.EMPTY_VALUE;
          };
      }
    else if (n.equals("fuzion.std.exit"))
      {
        result = (args) ->
          {
            int rc = args.get(1).i32Value();
            System.exit(rc);
            return Value.EMPTY_VALUE;
          };
      }
    else if (n.equals("fuzion.java.getStaticField0") ||
             n.equals("fuzion.java.getField0"      )    )
      {
        var statique = n.equals("fuzion.java.getStaticField0");
        var actualGenerics = innerClazz._type._generics;
        if ((actualGenerics == null) || (actualGenerics.size() != 1))
          {
            System.err.println("fuzion.java.getStaticField called with wrong number of actual generic arguments");
            System.exit(1);
          }
        Clazz resultClazz = innerClazz.actualClazz(actualGenerics.getFirst());
        result = (args) ->
          {
            if (!ENABLE_UNSAFE_INTRINSICS)
              {
                System.err.println("*** error: unsafe feature "+n+" disabled");
                System.exit(1);
              }
            Instance clazzOrThizI = (Instance) args.get(1);
            Instance fieldI = (Instance) args.get(2);
            String clazz = !statique ? null : (String) JavaInterface.instanceToJavaObject(clazzOrThizI);
            Object thiz  = statique  ? null :          JavaInterface.instanceToJavaObject(clazzOrThizI);
            String field = (String) JavaInterface.instanceToJavaObject(fieldI);
            return JavaInterface.getField(clazz, thiz, field, resultClazz);
          };
      }
    else if (n.equals("fuzion.java.callV0") ||
             n.equals("fuzion.java.callS0") ||
             n.equals("fuzion.java.callC0")    )
      {
        var virtual     = n.equals("fuzion.java.callV0");
        var statique    = n.equals("fuzion.java.callS0");
        var constructor = n.equals("fuzion.java.callC0");
        var actualGenerics = innerClazz._type._generics;
        Clazz resultClazz = innerClazz.actualClazz(actualGenerics.getFirst());
        result = (args) ->
          {
            if (!ENABLE_UNSAFE_INTRINSICS)
              {
                System.err.println("*** error: unsafe feature "+n+" disabled");
                System.exit(1);
              }
            int a = 1;
            var clNameI = virtual     ? null : (Instance) args.get(a++);
            var nameI   = constructor ? null : (Instance) args.get(a++);
            var sigI    =                      (Instance) args.get(a++);
            var thizI   = !virtual    ? null : (Instance) args.get(a++);
            var argz = args.get(a);
            String clName = clNameI == null ? null : (String) JavaInterface.instanceToJavaObject(clNameI);
            String name   = nameI   == null ? null : (String) JavaInterface.instanceToJavaObject(nameI  );
            String sig    =                          (String) JavaInterface.instanceToJavaObject(sigI   );
            Object thiz   = thizI   == null ? null :          JavaInterface.instanceToJavaObject(thizI  );
            return JavaInterface.call(clName, name, sig, thiz, argz, resultClazz);
          };
      }
    else if (n.equals("fuzion.java.stringToJavaObject0"))
      {
        result = (args) ->
          {
            if (!ENABLE_UNSAFE_INTRINSICS)
              {
                System.err.println("*** error: unsafe feature "+n+" disabled");
                System.exit(1);
              }
            var strA = args.get(1).arrayData();
            var ba = (byte[]) strA._array;
            String str = new String(ba, StandardCharsets.UTF_8);
            Clazz resultClazz = innerClazz.resultClazz();
            return JavaInterface.javaObjectToInstance(str, resultClazz);
          };
      }
    else if (n.equals("fuzion.java.i8ToJavaObject"))
      {
        result = (args) ->
          {
            if (!ENABLE_UNSAFE_INTRINSICS)
              {
                System.err.println("*** error: unsafe feature "+n+" disabled");
                System.exit(1);
              }
            var b = args.get(1).i8Value();
            var jb = Byte.valueOf((byte) b);
            Clazz resultClazz = innerClazz.resultClazz();
            return JavaInterface.javaObjectToInstance(jb, resultClazz);
          };
      }
    else if (n.equals("fuzion.java.u16ToJavaObject"))
      {
        result = (args) ->
          {
            if (!ENABLE_UNSAFE_INTRINSICS)
              {
                System.err.println("*** error: unsafe feature "+n+" disabled");
                System.exit(1);
              }
            var c = args.get(1).u16Value();
            var jc = Character.valueOf((char) c);
            Clazz resultClazz = innerClazz.resultClazz();
            return JavaInterface.javaObjectToInstance(jc, resultClazz);
          };
      }
    else if (n.equals("fuzion.java.i16ToJavaObject"))
      {
        result = (args) ->
          {
            if (!ENABLE_UNSAFE_INTRINSICS)
              {
                System.err.println("*** error: unsafe feature "+n+" disabled");
                System.exit(1);
              }
            var s = args.get(1).i16Value();
            var js = Short.valueOf((short) s);
            Clazz resultClazz = innerClazz.resultClazz();
            return JavaInterface.javaObjectToInstance(js, resultClazz);
          };
      }
    else if (n.equals("fuzion.java.i32ToJavaObject"))
      {
        result = (args) ->
          {
            if (!ENABLE_UNSAFE_INTRINSICS)
              {
                System.err.println("*** error: unsafe feature "+n+" disabled");
                System.exit(1);
              }
            var i = args.get(1).i32Value();
            var ji = Integer.valueOf(i);
            Clazz resultClazz = innerClazz.resultClazz();
            return JavaInterface.javaObjectToInstance(ji, resultClazz);
          };
      }
    else if (n.equals("fuzion.java.i64ToJavaObject"))
      {
        result = (args) ->
          {
            if (!ENABLE_UNSAFE_INTRINSICS)
              {
                System.err.println("*** error: unsafe feature "+n+" disabled");
                System.exit(1);
              }
            var l = args.get(1).i64Value();
            var jl = Long.valueOf(l);
            Clazz resultClazz = innerClazz.resultClazz();
            return JavaInterface.javaObjectToInstance(jl, resultClazz);
          };
      }
    else if (n.equals("fuzion.java.f32ToJavaObject"))
      {
        result = (args) ->
          {
            if (!ENABLE_UNSAFE_INTRINSICS)
              {
                System.err.println("*** error: unsafe feature "+n+" disabled");
                System.exit(1);
              }
            var f32 = args.get(1).f32Value();
            var jf = Float.valueOf(f32);
            Clazz resultClazz = innerClazz.resultClazz();
            return JavaInterface.javaObjectToInstance(jf, resultClazz);
          };
      }
    else if (n.equals("fuzion.java.f64ToJavaObject"))
      {
        result = (args) ->
          {
            if (!ENABLE_UNSAFE_INTRINSICS)
              {
                System.err.println("*** error: unsafe feature "+n+" disabled");
                System.exit(1);
              }
            var d = args.get(1).f64Value();
            var jd = Double.valueOf(d);
            Clazz resultClazz = innerClazz.resultClazz();
            return JavaInterface.javaObjectToInstance(jd, resultClazz);
          };
      }
    else if (n.equals("fuzion.java.boolToJavaObject"))
      {
        result = (args) ->
          {
            if (!ENABLE_UNSAFE_INTRINSICS)
              {
                System.err.println("*** error: unsafe feature "+n+" disabled");
                System.exit(1);
              }
            var b = args.get(1).boolValue();
            var jb = Boolean.valueOf(b);
            Clazz resultClazz = innerClazz.resultClazz();
            return JavaInterface.javaObjectToInstance(jb, resultClazz);
          };
      }
    else if (n.equals("sys.array.alloc"))
      {
        result = (args) ->
          {
            return sysArrayAlloc(/* size */ args.get(1).i32Value(),
                                 /* type */ innerClazz._outer);
          };
      }
    else if (n.equals("sys.array.get"))
      {
        result = (args) ->
          {
            return sysArrayGet(/* data  */ ((ArrayData)args.get(1)),
                               /* index */ args.get(2).i32Value(),
                               /* type  */ innerClazz._outer);
          };
      }
    else if (n.equals("sys.array.setel"))
      {
        result = (args) ->
          {
            sysArraySetEl(/* data  */ ((ArrayData)args.get(1)),
                          /* index */ args.get(2).i32Value(),
                          /* value */ args.get(3),
                          /* type  */ innerClazz._outer);
            return Value.EMPTY_VALUE;
          };
      }
    else if (n.equals("debug"        ) ||
             n.equals("debugLevel"   ) ||
             n.equals("safety"       ) ||
             n.equals("bool.prefix !") ||
             n.equals("bool.infix ||") ||
             n.equals("bool.infix &&") ||
             n.equals("bool.infix :")   )
      {
        if (Errors.count() == 0)
          {
            Errors.error(f.pos, "intrinsic feature not supported by backend",
                         "intrinsic '"+n+"' should be handled by front end");
          }
        result = (args) -> Value.NO_VALUE;
      }
    else if (n.equals("i8.as_i32"       )) { result = (args) -> new i32Value (              (                           args.get(0).i8Value() )); }
    else if (n.equals("i8.castTo_u8"    )) { result = (args) -> new u8Value  (       0xff & (                           args.get(0).i8Value() )); }
    else if (n.equals("i8.prefix -°"    )) { result = (args) -> new i8Value  ((int) (byte)  (                       -   args.get(0).i8Value() )); }
    else if (n.equals("i8.infix +°"     )) { result = (args) -> new i8Value  ((int) (byte)  (args.get(0).i8Value()  +   args.get(1).i8Value() )); }
    else if (n.equals("i8.infix -°"     )) { result = (args) -> new i8Value  ((int) (byte)  (args.get(0).i8Value()  -   args.get(1).i8Value() )); }
    else if (n.equals("i8.infix *°"     )) { result = (args) -> new i8Value  ((int) (byte)  (args.get(0).i8Value()  *   args.get(1).i8Value() )); }
    else if (n.equals("i8.div"          )) { result = (args) -> new i8Value  ((int) (byte)  (args.get(0).i8Value()  /   args.get(1).i8Value() )); }
    else if (n.equals("i8.mod"          )) { result = (args) -> new i8Value  ((int) (byte)  (args.get(0).i8Value()  %   args.get(1).i8Value() )); }
    else if (n.equals("i8.infix &"      )) { result = (args) -> new i8Value  (              (args.get(0).i8Value()  &   args.get(1).i8Value() )); }
    else if (n.equals("i8.infix |"      )) { result = (args) -> new i8Value  (              (args.get(0).i8Value()  |   args.get(1).i8Value() )); }
    else if (n.equals("i8.infix ^"      )) { result = (args) -> new i8Value  (              (args.get(0).i8Value()  ^   args.get(1).i8Value() )); }
    else if (n.equals("i8.infix >>"     )) { result = (args) -> new i8Value  (              (args.get(0).i8Value()  >>  args.get(1).i8Value() )); }
    else if (n.equals("i8.infix <<"     )) { result = (args) -> new i8Value  ((int) (byte)  (args.get(0).i8Value()  <<  args.get(1).i8Value() )); }
    else if (n.equals("i8.infix =="     )) { result = (args) -> new boolValue(              (args.get(0).i8Value()  ==  args.get(1).i8Value() )); }
    else if (n.equals("i8.infix !="     )) { result = (args) -> new boolValue(              (args.get(0).i8Value()  !=  args.get(1).i8Value() )); }
    else if (n.equals("i8.infix <"      )) { result = (args) -> new boolValue(              (args.get(0).i8Value()  <   args.get(1).i8Value() )); }
    else if (n.equals("i8.infix >"      )) { result = (args) -> new boolValue(              (args.get(0).i8Value()  >   args.get(1).i8Value() )); }
    else if (n.equals("i8.infix <="     )) { result = (args) -> new boolValue(              (args.get(0).i8Value()  <=  args.get(1).i8Value() )); }
    else if (n.equals("i8.infix >="     )) { result = (args) -> new boolValue(              (args.get(0).i8Value()  >=  args.get(1).i8Value() )); }
    else if (n.equals("i16.as_i32"      )) { result = (args) -> new i32Value (              (                           args.get(0).i16Value())); }
    else if (n.equals("i16.castTo_u16"  )) { result = (args) -> new u16Value (     0xffff & (                           args.get(0).i16Value())); }
    else if (n.equals("i16.prefix -°"   )) { result = (args) -> new i16Value ((int) (short) (                       -   args.get(0).i16Value())); }
    else if (n.equals("i16.infix +°"    )) { result = (args) -> new i16Value ((int) (short) (args.get(0).i16Value() +   args.get(1).i16Value())); }
    else if (n.equals("i16.infix -°"    )) { result = (args) -> new i16Value ((int) (short) (args.get(0).i16Value() -   args.get(1).i16Value())); }
    else if (n.equals("i16.infix *°"    )) { result = (args) -> new i16Value ((int) (short) (args.get(0).i16Value() *   args.get(1).i16Value())); }
    else if (n.equals("i16.div"         )) { result = (args) -> new i16Value ((int) (short) (args.get(0).i16Value() /   args.get(1).i16Value())); }
    else if (n.equals("i16.mod"         )) { result = (args) -> new i16Value ((int) (short) (args.get(0).i16Value() %   args.get(1).i16Value())); }
    else if (n.equals("i16.infix &"     )) { result = (args) -> new i16Value (              (args.get(0).i16Value() &   args.get(1).i16Value())); }
    else if (n.equals("i16.infix |"     )) { result = (args) -> new i16Value (              (args.get(0).i16Value() |   args.get(1).i16Value())); }
    else if (n.equals("i16.infix ^"     )) { result = (args) -> new i16Value (              (args.get(0).i16Value() ^   args.get(1).i16Value())); }
    else if (n.equals("i16.infix >>"    )) { result = (args) -> new i16Value (              (args.get(0).i16Value() >>  args.get(1).i16Value())); }
    else if (n.equals("i16.infix <<"    )) { result = (args) -> new i16Value ((int) (short) (args.get(0).i16Value() <<  args.get(1).i16Value())); }
    else if (n.equals("i16.infix =="    )) { result = (args) -> new boolValue(              (args.get(0).i16Value() ==  args.get(1).i16Value())); }
    else if (n.equals("i16.infix !="    )) { result = (args) -> new boolValue(              (args.get(0).i16Value() !=  args.get(1).i16Value())); }
    else if (n.equals("i16.infix <"     )) { result = (args) -> new boolValue(              (args.get(0).i16Value() <   args.get(1).i16Value())); }
    else if (n.equals("i16.infix >"     )) { result = (args) -> new boolValue(              (args.get(0).i16Value() >   args.get(1).i16Value())); }
    else if (n.equals("i16.infix <="    )) { result = (args) -> new boolValue(              (args.get(0).i16Value() <=  args.get(1).i16Value())); }
    else if (n.equals("i16.infix >="    )) { result = (args) -> new boolValue(              (args.get(0).i16Value() >=  args.get(1).i16Value())); }
    else if (n.equals("i32.as_i64"      )) { result = (args) -> new i64Value ((long)        (                           args.get(0).i32Value())); }
    else if (n.equals("i32.castTo_u32"  )) { result = (args) -> new u32Value (              (                           args.get(0).i32Value())); }
    else if (n.equals("i32.prefix -°"   )) { result = (args) -> new i32Value (              (                       -   args.get(0).i32Value())); }
    else if (n.equals("i32.infix +°"    )) { result = (args) -> new i32Value (              (args.get(0).i32Value() +   args.get(1).i32Value())); }
    else if (n.equals("i32.infix -°"    )) { result = (args) -> new i32Value (              (args.get(0).i32Value() -   args.get(1).i32Value())); }
    else if (n.equals("i32.infix *°"    )) { result = (args) -> new i32Value (              (args.get(0).i32Value() *   args.get(1).i32Value())); }
    else if (n.equals("i32.div"         )) { result = (args) -> new i32Value (              (args.get(0).i32Value() /   args.get(1).i32Value())); }
    else if (n.equals("i32.mod"         )) { result = (args) -> new i32Value (              (args.get(0).i32Value() %   args.get(1).i32Value())); }
    else if (n.equals("i32.infix &"     )) { result = (args) -> new i32Value (              (args.get(0).i32Value() &   args.get(1).i32Value())); }
    else if (n.equals("i32.infix |"     )) { result = (args) -> new i32Value (              (args.get(0).i32Value() |   args.get(1).i32Value())); }
    else if (n.equals("i32.infix ^"     )) { result = (args) -> new i32Value (              (args.get(0).i32Value() ^   args.get(1).i32Value())); }
    else if (n.equals("i32.infix >>"    )) { result = (args) -> new i32Value (              (args.get(0).i32Value() >>  args.get(1).i32Value())); }
    else if (n.equals("i32.infix <<"    )) { result = (args) -> new i32Value (              (args.get(0).i32Value() <<  args.get(1).i32Value())); }
    else if (n.equals("i32.infix =="    )) { result = (args) -> new boolValue(              (args.get(0).i32Value() ==  args.get(1).i32Value())); }
    else if (n.equals("i32.infix !="    )) { result = (args) -> new boolValue(              (args.get(0).i32Value() !=  args.get(1).i32Value())); }
    else if (n.equals("i32.infix <"     )) { result = (args) -> new boolValue(              (args.get(0).i32Value() <   args.get(1).i32Value())); }
    else if (n.equals("i32.infix >"     )) { result = (args) -> new boolValue(              (args.get(0).i32Value() >   args.get(1).i32Value())); }
    else if (n.equals("i32.infix <="    )) { result = (args) -> new boolValue(              (args.get(0).i32Value() <=  args.get(1).i32Value())); }
    else if (n.equals("i32.infix >="    )) { result = (args) -> new boolValue(              (args.get(0).i32Value() >=  args.get(1).i32Value())); }
    else if (n.equals("i64.castTo_u64"  )) { result = (args) -> new u64Value (              (                           args.get(0).i64Value())); }
    else if (n.equals("i64.prefix -°"   )) { result = (args) -> new i64Value (              (                       -   args.get(0).u64Value())); }
    else if (n.equals("i64.infix +°"    )) { result = (args) -> new i64Value (              (args.get(0).i64Value() +   args.get(1).i64Value())); }
    else if (n.equals("i64.infix -°"    )) { result = (args) -> new i64Value (              (args.get(0).i64Value() -   args.get(1).i64Value())); }
    else if (n.equals("i64.infix *°"    )) { result = (args) -> new i64Value (              (args.get(0).i64Value() *   args.get(1).i64Value())); }
    else if (n.equals("i64.div"         )) { result = (args) -> new i64Value (              (args.get(0).i64Value() /   args.get(1).i64Value())); }
    else if (n.equals("i64.mod"         )) { result = (args) -> new i64Value (              (args.get(0).i64Value() %   args.get(1).i64Value())); }
    else if (n.equals("i64.infix &"     )) { result = (args) -> new i64Value (              (args.get(0).i64Value() &   args.get(1).i64Value())); }
    else if (n.equals("i64.infix |"     )) { result = (args) -> new i64Value (              (args.get(0).i64Value() |   args.get(1).i64Value())); }
    else if (n.equals("i64.infix ^"     )) { result = (args) -> new i64Value (              (args.get(0).i64Value() ^   args.get(1).i64Value())); }
    else if (n.equals("i64.infix >>"    )) { result = (args) -> new i64Value (              (args.get(0).i64Value() >>  args.get(1).i64Value())); }
    else if (n.equals("i64.infix <<"    )) { result = (args) -> new i64Value (              (args.get(0).i64Value() <<  args.get(1).i64Value())); }
    else if (n.equals("i64.infix =="    )) { result = (args) -> new boolValue(              (args.get(0).i64Value() ==  args.get(1).i64Value())); }
    else if (n.equals("i64.infix !="    )) { result = (args) -> new boolValue(              (args.get(0).i64Value() !=  args.get(1).i64Value())); }
    else if (n.equals("i64.infix <"     )) { result = (args) -> new boolValue(              (args.get(0).i64Value() <   args.get(1).i64Value())); }
    else if (n.equals("i64.infix >"     )) { result = (args) -> new boolValue(              (args.get(0).i64Value() >   args.get(1).i64Value())); }
    else if (n.equals("i64.infix <="    )) { result = (args) -> new boolValue(              (args.get(0).i64Value() <=  args.get(1).i64Value())); }
    else if (n.equals("i64.infix >="    )) { result = (args) -> new boolValue(              (args.get(0).i64Value() >=  args.get(1).i64Value())); }
    else if (n.equals("u8.as_i32"       )) { result = (args) -> new i32Value (              (                           args.get(0).u8Value() )); }
    else if (n.equals("u8.castTo_i8"    )) { result = (args) -> new i8Value  ((int) (byte)  (                           args.get(0).u8Value() )); }
    else if (n.equals("u8.prefix -°"    )) { result = (args) -> new u8Value  (       0xff & (                       -   args.get(0).u8Value() )); }
    else if (n.equals("u8.infix +°"     )) { result = (args) -> new u8Value  (       0xff & (args.get(0).u8Value()  +   args.get(1).u8Value() )); }
    else if (n.equals("u8.infix -°"     )) { result = (args) -> new u8Value  (       0xff & (args.get(0).u8Value()  -   args.get(1).u8Value() )); }
    else if (n.equals("u8.infix *°"     )) { result = (args) -> new u8Value  (       0xff & (args.get(0).u8Value()  *   args.get(1).u8Value() )); }
    else if (n.equals("u8.div"          )) { result = (args) -> new u8Value  (Integer.divideUnsigned   (args.get(0).u8Value(), args.get(1).u8Value())); }
    else if (n.equals("u8.mod"          )) { result = (args) -> new u8Value  (Integer.remainderUnsigned(args.get(0).u8Value(), args.get(1).u8Value())); }
    else if (n.equals("u8.infix &"      )) { result = (args) -> new u8Value  (              (args.get(0).u8Value()  &   args.get(1).u8Value() )); }
    else if (n.equals("u8.infix |"      )) { result = (args) -> new u8Value  (              (args.get(0).u8Value()  |   args.get(1).u8Value() )); }
    else if (n.equals("u8.infix ^"      )) { result = (args) -> new u8Value  (              (args.get(0).u8Value()  ^   args.get(1).u8Value() )); }
    else if (n.equals("u8.infix >>"     )) { result = (args) -> new u8Value  (              (args.get(0).u8Value()  >>> args.get(1).u8Value() )); }
    else if (n.equals("u8.infix <<"     )) { result = (args) -> new u8Value  (       0xff & (args.get(0).u8Value()  <<  args.get(1).u8Value() )); }
    else if (n.equals("u8.infix =="     )) { result = (args) -> new boolValue(              (args.get(0).u8Value()  ==  args.get(1).u8Value() )); }
    else if (n.equals("u8.infix !="     )) { result = (args) -> new boolValue(              (args.get(0).u8Value()  !=  args.get(1).u8Value() )); }
    else if (n.equals("u8.infix <"      )) { result = (args) -> new boolValue(Integer.compareUnsigned(args.get(0).u8Value(), args.get(1).u8Value()) <  0); }
    else if (n.equals("u8.infix >"      )) { result = (args) -> new boolValue(Integer.compareUnsigned(args.get(0).u8Value(), args.get(1).u8Value()) >  0); }
    else if (n.equals("u8.infix <="     )) { result = (args) -> new boolValue(Integer.compareUnsigned(args.get(0).u8Value(), args.get(1).u8Value()) <= 0); }
    else if (n.equals("u8.infix >="     )) { result = (args) -> new boolValue(Integer.compareUnsigned(args.get(0).u8Value(), args.get(1).u8Value()) >= 0); }
    else if (n.equals("u16.as_i32"      )) { result = (args) -> new i32Value (              (                           args.get(0).u16Value())); }
    else if (n.equals("u16.low8bits"    )) { result = (args) -> new u8Value  (       0xff & (                           args.get(0).u16Value())); }
    else if (n.equals("u16.castTo_i16"  )) { result = (args) -> new i16Value ((short)       (                           args.get(0).u16Value())); }
    else if (n.equals("u16.prefix -°"   )) { result = (args) -> new u16Value (     0xffff & (                       -   args.get(0).u16Value())); }
    else if (n.equals("u16.infix +°"    )) { result = (args) -> new u16Value (     0xffff & (args.get(0).u16Value() +   args.get(1).u16Value())); }
    else if (n.equals("u16.infix -°"    )) { result = (args) -> new u16Value (     0xffff & (args.get(0).u16Value() -   args.get(1).u16Value())); }
    else if (n.equals("u16.infix *°"    )) { result = (args) -> new u16Value (     0xffff & (args.get(0).u16Value() *   args.get(1).u16Value())); }
    else if (n.equals("u16.div"         )) { result = (args) -> new u16Value (Integer.divideUnsigned   (args.get(0).u16Value(), args.get(1).u16Value())); }
    else if (n.equals("u16.mod"         )) { result = (args) -> new u16Value (Integer.remainderUnsigned(args.get(0).u16Value(), args.get(1).u16Value())); }
    else if (n.equals("u16.infix &"     )) { result = (args) -> new u16Value (              (args.get(0).u16Value() &   args.get(1).u16Value())); }
    else if (n.equals("u16.infix |"     )) { result = (args) -> new u16Value (              (args.get(0).u16Value() |   args.get(1).u16Value())); }
    else if (n.equals("u16.infix ^"     )) { result = (args) -> new u16Value (              (args.get(0).u16Value() ^   args.get(1).u16Value())); }
    else if (n.equals("u16.infix >>"    )) { result = (args) -> new u16Value (              (args.get(0).u16Value() >>> args.get(1).u16Value())); }
    else if (n.equals("u16.infix <<"    )) { result = (args) -> new u16Value (     0xffff & (args.get(0).u16Value() <<  args.get(1).u16Value())); }
    else if (n.equals("u16.infix =="    )) { result = (args) -> new boolValue(              (args.get(0).u16Value() ==  args.get(1).u16Value())); }
    else if (n.equals("u16.infix !="    )) { result = (args) -> new boolValue(              (args.get(0).u16Value() !=  args.get(1).u16Value())); }
    else if (n.equals("u16.infix <"     )) { result = (args) -> new boolValue(Integer.compareUnsigned(args.get(0).u16Value(), args.get(1).u16Value()) <  0); }
    else if (n.equals("u16.infix >"     )) { result = (args) -> new boolValue(Integer.compareUnsigned(args.get(0).u16Value(), args.get(1).u16Value()) >  0); }
    else if (n.equals("u16.infix <="    )) { result = (args) -> new boolValue(Integer.compareUnsigned(args.get(0).u16Value(), args.get(1).u16Value()) <= 0); }
    else if (n.equals("u16.infix >="    )) { result = (args) -> new boolValue(Integer.compareUnsigned(args.get(0).u16Value(), args.get(1).u16Value()) >= 0); }
    else if (n.equals("u32.as_i64"      )) { result = (args) -> new i64Value (Integer.toUnsignedLong(args.get(0).u32Value())); }
    else if (n.equals("u32.low8bits"    )) { result = (args) -> new u8Value  (       0xff & (                           args.get(0).u32Value())); }
    else if (n.equals("u32.low16bits"   )) { result = (args) -> new u16Value (     0xffff & (                           args.get(0).u32Value())); }
    else if (n.equals("u32.castTo_i32"  )) { result = (args) -> new i32Value (              (                           args.get(0).u32Value())); }
    else if (n.equals("u32.prefix -°"   )) { result = (args) -> new u32Value (              (                       -   args.get(0).u32Value())); }
    else if (n.equals("u32.infix +°"    )) { result = (args) -> new u32Value (              (args.get(0).u32Value() +   args.get(1).u32Value())); }
    else if (n.equals("u32.infix -°"    )) { result = (args) -> new u32Value (              (args.get(0).u32Value() -   args.get(1).u32Value())); }
    else if (n.equals("u32.infix *°"    )) { result = (args) -> new u32Value (              (args.get(0).u32Value() *   args.get(1).u32Value())); }
    else if (n.equals("u32.div"         )) { result = (args) -> new u32Value (Integer.divideUnsigned   (args.get(0).u32Value(), args.get(1).u32Value())); }
    else if (n.equals("u32.mod"         )) { result = (args) -> new u32Value (Integer.remainderUnsigned(args.get(0).u32Value(), args.get(1).u32Value())); }
    else if (n.equals("u32.infix &"     )) { result = (args) -> new u32Value (              (args.get(0).u32Value() &   args.get(1).u32Value())); }
    else if (n.equals("u32.infix |"     )) { result = (args) -> new u32Value (              (args.get(0).u32Value() |   args.get(1).u32Value())); }
    else if (n.equals("u32.infix ^"     )) { result = (args) -> new u32Value (              (args.get(0).u32Value() ^   args.get(1).u32Value())); }
    else if (n.equals("u32.infix >>"    )) { result = (args) -> new u32Value (              (args.get(0).u32Value() >>> args.get(1).u32Value())); }
    else if (n.equals("u32.infix <<"    )) { result = (args) -> new u32Value (              (args.get(0).u32Value() <<  args.get(1).u32Value())); }
    else if (n.equals("u32.infix =="    )) { result = (args) -> new boolValue(              (args.get(0).u32Value() ==  args.get(1).u32Value())); }
    else if (n.equals("u32.infix !="    )) { result = (args) -> new boolValue(              (args.get(0).u32Value() !=  args.get(1).u32Value())); }
    else if (n.equals("u32.infix <"     )) { result = (args) -> new boolValue(Integer.compareUnsigned(args.get(0).u32Value(), args.get(1).u32Value()) <  0); }
    else if (n.equals("u32.infix >"     )) { result = (args) -> new boolValue(Integer.compareUnsigned(args.get(0).u32Value(), args.get(1).u32Value()) >  0); }
    else if (n.equals("u32.infix <="    )) { result = (args) -> new boolValue(Integer.compareUnsigned(args.get(0).u32Value(), args.get(1).u32Value()) <= 0); }
    else if (n.equals("u32.infix >="    )) { result = (args) -> new boolValue(Integer.compareUnsigned(args.get(0).u32Value(), args.get(1).u32Value()) >= 0); }
    else if (n.equals("u64.low8bits"    )) { result = (args) -> new u8Value  (       0xff & ((int)                      args.get(0).u64Value())); }
    else if (n.equals("u64.low16bits"   )) { result = (args) -> new u16Value (     0xffff & ((int)                      args.get(0).u64Value())); }
    else if (n.equals("u64.low32bits"   )) { result = (args) -> new u32Value ((int)         (                           args.get(0).u64Value())); }
    else if (n.equals("u64.castTo_i64"  )) { result = (args) -> new i64Value (              (                           args.get(0).u64Value())); }
    else if (n.equals("u64.prefix -°"   )) { result = (args) -> new u64Value (              (                       -   args.get(0).u64Value())); }
    else if (n.equals("u64.infix +°"    )) { result = (args) -> new u64Value (              (args.get(0).u64Value() +   args.get(1).u64Value())); }
    else if (n.equals("u64.infix -°"    )) { result = (args) -> new u64Value (              (args.get(0).u64Value() -   args.get(1).u64Value())); }
    else if (n.equals("u64.infix *°"    )) { result = (args) -> new u64Value (              (args.get(0).u64Value() *   args.get(1).u64Value())); }
    else if (n.equals("u64.div"         )) { result = (args) -> new u64Value (Long.divideUnsigned   (args.get(0).u64Value(), args.get(1).u64Value())); }
    else if (n.equals("u64.mod"         )) { result = (args) -> new u64Value (Long.remainderUnsigned(args.get(0).u64Value(), args.get(1).u64Value())); }
    else if (n.equals("u64.infix &"     )) { result = (args) -> new u64Value (              (args.get(0).u64Value() &   args.get(1).u64Value())); }
    else if (n.equals("u64.infix |"     )) { result = (args) -> new u64Value (              (args.get(0).u64Value() |   args.get(1).u64Value())); }
    else if (n.equals("u64.infix ^"     )) { result = (args) -> new u64Value (              (args.get(0).u64Value() ^   args.get(1).u64Value())); }
    else if (n.equals("u64.infix >>"    )) { result = (args) -> new u64Value (              (args.get(0).u64Value() >>> args.get(1).u64Value())); }
    else if (n.equals("u64.infix <<"    )) { result = (args) -> new u64Value (              (args.get(0).u64Value() <<  args.get(1).u64Value())); }
    else if (n.equals("u64.infix =="    )) { result = (args) -> new boolValue(              (args.get(0).u64Value() ==  args.get(1).u64Value())); }
    else if (n.equals("u64.infix !="    )) { result = (args) -> new boolValue(              (args.get(0).u64Value() !=  args.get(1).u64Value())); }
    else if (n.equals("u64.infix <"     )) { result = (args) -> new boolValue(Long.compareUnsigned(args.get(0).u64Value(), args.get(1).u64Value()) <  0); }
    else if (n.equals("u64.infix >"     )) { result = (args) -> new boolValue(Long.compareUnsigned(args.get(0).u64Value(), args.get(1).u64Value()) >  0); }
    else if (n.equals("u64.infix <="    )) { result = (args) -> new boolValue(Long.compareUnsigned(args.get(0).u64Value(), args.get(1).u64Value()) <= 0); }
    else if (n.equals("u64.infix >="    )) { result = (args) -> new boolValue(Long.compareUnsigned(args.get(0).u64Value(), args.get(1).u64Value()) >= 0); }
    else if (n.equals("Object.infix !==")) { result = (args) -> new boolValue(args.get(0) !=  args.get(1)); }
    else if (n.equals("Object.infix ===")) { result = (args) -> new boolValue(args.get(0) ==  args.get(1)); }
    else if (n.equals("Object.hashCode" )) { result = (args) -> new i32Value (args.get(0).toString().hashCode()); }
    else if (n.equals("Object.asString" )) { result = (args) -> Interpreter.value(args.get(0).toString());
      // NYI: This could be more useful by giving the object's class, an id, public fields, etc.
      }
    else
      {
        Errors.error(f.pos,
                     "Intrinsic feature not supported",
                     "Missing intrinsic feature: " + f.qualifiedName());
        result = (args) -> Value.NO_VALUE;
      }
    return result;
  }


  static Type elementType(Clazz arrayClazz)
  {
    // NYI: Properly determine generic argument type of array
    var arrayType = arrayClazz._type;
    if (arrayType == Types.resolved.t_conststring /* NYI: Hack */)
      {
        return Types.resolved.t_i32;
      }
    else
      {
        return arrayType._generics.getFirst();
      }
  }

  static ArrayData sysArrayAlloc(int sz,
                                Clazz arrayClazz)
  {
    // NYI: Properly determine generic argument type of array
    var elementType = elementType(arrayClazz);
    if      (elementType == Types.resolved.t_i8  ) { return new ArrayData(new byte   [sz]); }
    else if (elementType == Types.resolved.t_i16 ) { return new ArrayData(new short  [sz]); }
    else if (elementType == Types.resolved.t_i32 ) { return new ArrayData(new int    [sz]); }
    else if (elementType == Types.resolved.t_i64 ) { return new ArrayData(new long   [sz]); }
    else if (elementType == Types.resolved.t_u8  ) { return new ArrayData(new byte   [sz]); }
    else if (elementType == Types.resolved.t_u16 ) { return new ArrayData(new char   [sz]); }
    else if (elementType == Types.resolved.t_u32 ) { return new ArrayData(new int    [sz]); }
    else if (elementType == Types.resolved.t_u64 ) { return new ArrayData(new long   [sz]); }
    else if (elementType == Types.resolved.t_bool) { return new ArrayData(new boolean[sz]); }
    else                                           { return new ArrayData(new Value  [sz]); }
  }

  static void sysArraySetEl(ArrayData ad,
                            int x,
                            Value v,
                            Clazz arrayClazz)
  {
    // NYI: Properly determine generic argument type of array
    var elementType = elementType(arrayClazz);
    ad.checkIndex(x);
    if      (elementType == Types.resolved.t_i8  ) { ((byte   [])ad._array)[x] = (byte   ) v.i8Value();   }
    else if (elementType == Types.resolved.t_i16 ) { ((short  [])ad._array)[x] = (short  ) v.i16Value();  }
    else if (elementType == Types.resolved.t_i32 ) { ((int    [])ad._array)[x] =           v.i32Value();  }
    else if (elementType == Types.resolved.t_i64 ) { ((long   [])ad._array)[x] =           v.i64Value();  }
    else if (elementType == Types.resolved.t_u8  ) { ((byte   [])ad._array)[x] = (byte   ) v.u8Value();   }
    else if (elementType == Types.resolved.t_u16 ) { ((char   [])ad._array)[x] = (char   ) v.u16Value();  }
    else if (elementType == Types.resolved.t_u32 ) { ((int    [])ad._array)[x] =           v.u32Value();  }
    else if (elementType == Types.resolved.t_u64 ) { ((long   [])ad._array)[x] =           v.u64Value();  }
    else if (elementType == Types.resolved.t_bool) { ((boolean[])ad._array)[x] =           v.boolValue(); }
    else                                           { ((Value  [])ad._array)[x] =           v;             }
  }


  static Value sysArrayGet(ArrayData ad,
                           int x,
                           Clazz arrayClazz)
  {
    // NYI: Properly determine generic argument type of array
    var elementType = elementType(arrayClazz);
    ad.checkIndex(x);
    if      (elementType == Types.resolved.t_i8  ) { return new i8Value  (((byte   [])ad._array)[x]       ); }
    else if (elementType == Types.resolved.t_i16 ) { return new i16Value (((short  [])ad._array)[x]       ); }
    else if (elementType == Types.resolved.t_i32 ) { return new i32Value (((int    [])ad._array)[x]       ); }
    else if (elementType == Types.resolved.t_i64 ) { return new i64Value (((long   [])ad._array)[x]       ); }
    else if (elementType == Types.resolved.t_u8  ) { return new u8Value  (((byte   [])ad._array)[x] & 0xff); }
    else if (elementType == Types.resolved.t_u16 ) { return new u16Value (((char   [])ad._array)[x]       ); }
    else if (elementType == Types.resolved.t_u32 ) { return new u32Value (((int    [])ad._array)[x]       ); }
    else if (elementType == Types.resolved.t_u64 ) { return new u64Value (((long   [])ad._array)[x]       ); }
    else if (elementType == Types.resolved.t_bool) { return new boolValue(((boolean[])ad._array)[x]       ); }
    else                                           { return              ((Value   [])ad._array)[x]        ; }
  }

}

/* end of file */
