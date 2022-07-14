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
 * Source of class DFA
 *
 *---------------------------------------------------------------------*/

package dev.flang.fuir.analysis;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import java.util.Set;
import java.util.TreeMap;

import dev.flang.fuir.FUIR;

import dev.flang.util.ANY;
import dev.flang.util.Errors;
import dev.flang.util.List;
import dev.flang.util.Pair;


/**
 * DFA creates a data flow analysis based on the FUIR representation of a Fuzion
 * application.
 *
 * @author Fridtjof Siebert (siebert@tokiwa.software)
 */
public class DFA extends ANY
{


  /*----------------------------  interfaces  ---------------------------*/


  /**
   * Functional interface to crate intrinsics.
   */
  interface IntrinsicDFA
  {
    Value analyze(Call c);
  }


  /*-----------------------------  classes  -----------------------------*/


  /**
   * Dummy unit type as type parameter for AbstractInterpreter.ProcessStatement.
   */
  static class Unit
  {
  }


  /**
   * Statement processor used with AbstractInterpreter to perform DFA analysis
   */
  class Analyze extends AbstractInterpreter.ProcessStatement<Value,Unit>
  {


    /**
     * The Call we are analysing.
     */
    final Call _call;


    /**
     * Create processor for an abstract interpreter doing DFA analysis of the
     * given call's code.
     */
    Analyze(Call call)
    {
      _call = call;
    }



    /**
     * Join a List of RESULT from subsequent statements into a compound
     * statement.  For a code generator, this could, e.g., join statements "a :=
     * 3;" and "b(x);" into a block "{ a := 3; b(x); }".
     */
    public Unit sequence(List<Unit> l)
    {
      return _unit_;
    }


    /*
     * Produce the unit type value.  This is used as a placeholder
     * for the universe instance as well as for the instance 'unit'.
     */
    public Value unitValue()
    {
      return Value.UNIT;
    }


    /**
     * Called before each statement is processed. May be used to, e.g., produce
     * tracing code for debugging or a comment.
     */
    public Unit statementHeader(int cl, int c, int i)
    {
      if (_reportResults && VERBOSE)
        {
          System.out.println("DFA for "+_fuir.clazzAsString(cl)+"("+_fuir.clazzArgCount(cl)+" args) at "+c+"."+i+": "+_fuir.codeAtAsString(cl,c,i));
        }
      return _unit_;
    }


    /**
     * A comment, adds human readable information
     */
    public Unit comment(String s)
    {
      return _unit_;
    }


    /**
     * no operation, like comment, but without giving any comment.
     */
    public Unit nop()
    {
      return _unit_;
    }


    /**
     * Determine the address of a given value.  This is used on a call to an
     * inner feature to pass a reference to the outer value type instance.
     */
    public Pair<Value, Unit> adrOf(Value v)
    {
      return new Pair<>(v.adrOf(), _unit_);
    }


    /**
     * Perform an assignment val to field f in instance rt
     *
     * @param tc clazz id of the target instance
     *
     * @param f clazz id of the assigned field
     *
     * @param rt clazz is of the field type
     *
     * @param tvalue the target instance
     *
     * @param val the new value to be assigned to the field.
     *
     * @return resulting code of this assignment.
     */
    public Unit assignStatic(int tc, int f, int rt, Value tvalue, Value val)
    {
      tvalue.setField(f, val);
      return _unit_;
    }


    /**
     * Perform an assignment of avalue to a field in tvalue. The type of tvalue
     * might be dynamic (a refernce). See FUIR.acess*().
     */
    public Unit assign(int cl, int c, int i, Value tvalue, Value avalue)
    {
      var res = access(cl, c, i, tvalue, new List<>(avalue));
      return _unit_;
    }


    /**
     * Perform a call of a feature with target instance tvalue with given
     * arguments.. The type of tvalue might be dynamic (a refernce). See
     * FUIR.acess*().
     *
     * Result._v0 may be null to indicate that code generation should stop here
     * (due to an error or tail recursion optimization).
     */
    public Pair<Value, Unit> call(int cl, int c, int i, Value tvalue, List<Value> args)
    {
      var cc0 = _fuir.accessedClazz  (cl, c, i);
      var res = Value.UNIT;
      if (_fuir.clazzContract(cc0, FUIR.ContractKind.Pre, 0) != -1)
        {
          res = call0(cl, tvalue, args, c, i, cc0, true);
        }
      if (res != null && !_fuir.callPreconditionOnly(cl, c, i))
        {
          res = access(cl, c, i, tvalue, args);
        }
      return new Pair<>(res, _unit_);
    }


    /**
     * Analyze an access (call or write) a feature.
     *
     * @param cl clazz id
     *
     * @param c the code block to compile
     *
     * @param i index of the access statement, must be ExprKind.Assign or ExprKind.Call
     *
     * @param tvalue the target of this call, Value.UNIT if none.
     *
     * @param args the arguments of this call, or, in case of an assignment, a
     * list of one element containing value to be assigned.
     *
     * @return result value of the access
     */
    Value access(int cl, int c, int i, Value tvalue, List<Value> args)
    {
      Value res;
      var cc0 = _fuir.accessedClazz  (cl, c, i);

      if (_fuir.accessIsDynamic(cl, c, i))
        {
          var ccs = _fuir.accessedClazzes(cl, c, i);
          var found = new boolean[] { false };
          var resf = new Value[] { null };
          for (var ccii = 0; ccii < ccs.length; ccii += 2)
            {
              var cci = ccii;
              var tt = ccs[cci  ];
              var cc = ccs[cci+1];
              tvalue.forAll(t -> {
                  if (t instanceof Instance   ti && ti._clazz == tt ||
                      t instanceof BoxedValue tb && tb._clazz == tt)
                    {
                      found[0] = true;
                      var r = access0(cl, c, i, t, args, cc);
                      if (r != null)
                        {
                          resf[0] = resf[0] == null ? r : resf[0].join(r);
                        }
                    }
                });
            }
          if (found[0])
            {
              res = resf[0];
            }
          else
            {
              // NYI: proper error reporting
              System.out.println("NYI: in "+_fuir.clazzAsString(cl)+" no targets for call to "+_fuir.codeAtAsString(cl, c, i)+" target "+tvalue);
              Thread.dumpStack();
              res = null;
            }
        }
      else if (_fuir.clazzNeedsCode(cc0))
        {
          res = access0(cl, c, i, tvalue, args, cc0);
        }
      else
        {
          System.out.println("NYI: DFA call to nowhere for "+_fuir.codeAtAsString(cl, c, i));
          /* NYI: proper error reporting
        result = reportErrorInCode("no code generated for static access to %s within %s",
                                   CExpr.string(_fuir.clazzAsString(cc0)),
                                   CExpr.string(_fuir.clazzAsString(cl )));
          */
          res = null;
        }
      return res;
    }


    /**
     * Helper routine for access (above) to perform a static access (cal or write).
     */
    Value access0(int cl, int c, int i, Value tvalue, List<Value> args, int cc)
    {
      var isCall = _fuir.codeAt(c, i) == FUIR.ExprKind.Call;
      Value r;
      if (isCall)
        {
          r = call0(cl, tvalue, args, c, i, cc, false);
        }
      else
        {
          if (!_fuir.clazzIsUnitType(_fuir.clazzResultClazz(cc)))
            {
              if (_reportResults && VERBOSE)
                {
                  System.out.println("DFA for "+_fuir.clazzAsString(cl)+"("+_fuir.clazzArgCount(cl)+" args) at "+c+"."+i+": "+_fuir.codeAtAsString(cl,c,i)+": " +
                                     tvalue + ".set("+_fuir.clazzAsString(cc)+") := " + args.get(0));
                }
              tvalue.setField(cc, args.get(0));
            }
          r = Value.UNIT;
        }
      return r;
    }


    /**
     * Helper for call to handle non-dynamic call to cc (or cc's precondition)
     *
     * @param cl clazz id of clazz containing the call
     *
     * @param stack the stack containing the current arguments waiting to be used
     *
     * @param c the code block to compile
     *
     * @param i the index of the call within c
     *
     * @param cc clazz that is called
     *
     * @param pre true to call the precondition of cl instead of cl.
     *
     * @return result values of the call
     */
    Value call0(int cl, Value tvalue, List<Value> args, int c, int i, int cc, boolean pre)
    {
      Value res = null;
      switch (pre ? FUIR.FeatureKind.Routine : _fuir.clazzKind(cc))
        {
        case Abstract :
          Errors.error("Call to abstract feature encountered.",
                       "Found call to  " + _fuir.clazzAsString(cc));
        case Routine  :
        case Intrinsic:
          {
            if (_fuir.clazzNeedsCode(cc))
              {
                var ca = newCall(cc, pre, tvalue, args, _call._env, _call);
                res = ca.result();
                if (_reportResults && VERBOSE)
                  {
                    System.out.println("DFA for "+_fuir.clazzAsString(cl)+"("+_fuir.clazzArgCount(cl)+" args) at "+c+"."+i+": "+_fuir.codeAtAsString(cl,c,i)+": " + ca);
                  }
              }
            break;
          }
        case Field:
          {
            res = tvalue.readField(DFA.this, _fuir.accessTargetClazz(cl, c, i), cc);
            if (_reportResults && VERBOSE)
              {
                System.out.println("DFA for "+_fuir.clazzAsString(cl)+"("+_fuir.clazzArgCount(cl)+" args) at "+c+"."+i+": "+_fuir.codeAtAsString(cl,c,i)+": " +
                                   tvalue + ".get(" + _fuir.clazzAsString(cc) + ") => " + res);
              }
            break;
          }
        default:       throw new Error("This should not happen: Unknown feature kind: " + _fuir.clazzKind(cc));
        }
      return res;
    }


    /**
     * For a given value v of value type vc create a boxed ref value of type rc.
     */
    public Pair<Value, Unit> box(Value val, int vc, int rc)
    {
      var boxed = val.box(vc, rc);
      return new Pair<>(boxed, _unit_);
    }


    /**
     * For a given reference value v create an unboxed value of type vc.
     */
    public Pair<Value, Unit> unbox(Value val, int orc)
    {
      var unboxed = val.unbox(orc);
      return new Pair<>(unboxed, _unit_);
    }


    /**
     * Get the current instance
     */
    public Pair<Value, Unit> current(int cl)
    {
      return new Pair<>(_call._instance, _unit_);
    }


    /**
     * Get the outer instance
     */
    public Pair<Value, Unit> outer(int cl)
    {
      return new Pair<>(_call._target, _unit_);
    }

    /**
     * Get the argument #i
     */
    public Value arg(int cl, int i)
    {
      return _call._args.get(i);
    }


    /**
     * Get a constant value of type constCl with given byte data d.
     */
    public Pair<Value, Unit> constData(int constCl, byte[] d)
    {
      var o = _unit_;
      var r = switch (_fuir.getSpecialId(constCl))
        {
        case c_bool -> d[0] == 1 ? Value.TRUE : Value.FALSE;
        case c_i8   ,
             c_i16  ,
             c_i32  ,
             c_i64  ,
             c_u8   ,
             c_u16  ,
             c_u32  ,
             c_u64  ,
             c_f32  ,
             c_f64  -> new NumericValue(DFA.this, constCl, ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN));
        case c_conststring -> newConstString(d, _call);
        default ->
        {
          Errors.error("Unsupported constant in DFA analysis.",
                       "DFA cannot handle constant of clazz '" + _fuir.clazzAsString(constCl) + "' ");
          yield null;
        }
        };
      return new Pair(r, o);
    }


    /**
     * Perform a match on value subv.
     */
    public Pair<Value, Unit> match(AbstractInterpreter ai, int cl, int c, int i, Value subv)
    {
      Value r = null; // result value null <=> does not return.  Will be set to Value.UNIT if returning case was found.
      var subjClazz = _fuir.matchStaticSubject(cl, c, i);
      for (var mc = 0; mc < _fuir.matchCaseCount(c, i); mc++)
        {
          // arrays to permit modification
          var takenA    = new boolean[] { false };
          var untaggedA = new Value  [] { null };
          for (var t : _fuir.matchCaseTags(cl, c, i, mc))
            {
              subv.forAll(s -> {
                  var taken = false;
                  Value untagged = null;
                  // NYI: cleanup: remove special handling vor boolean, Value.BOOL/TRUE/FALSE should be instances of TaggedValue
                  if (_fuir.getSpecialId(subjClazz) == FUIR.SpecialClazzes.c_bool &&
                      (s == Value.BOOL ||
                       s == Value.TRUE ||
                       s == Value.FALSE ))
                    {
                      taken =
                        (s == Value.BOOL) ||
                        (s == Value.TRUE) && (t == 1) ||
                        (s == Value.FALSE) && (t == 0);
                      untagged = taken ? ((t == 1) ? Value.TRUE : Value.FALSE) : null;
                    }
                  else if (s instanceof TaggedValue tv)
                    {
                      if (tv._tag == t)
                        {
                          taken = true;
                          untagged = tv._original;
                        }
                    }
                  else
                    {
                      throw new Error("DFA encountered Unexpected value in match: " + s.getClass() +
                                      " for match of type " + _fuir.clazzAsString(subjClazz));
                    }
                  takenA[0] = takenA[0] || taken;
                  if (taken && untagged != null)
                    {
                      if (untaggedA[0] == null)
                        {
                          untaggedA[0] = untagged;
                        }
                      else
                        {
                          untaggedA[0] = untaggedA[0].join(untagged);
                        }
                    }
                });

            }
          var taken = takenA[0];
          var untagged = untaggedA[0];
          if (_reportResults && VERBOSE)
            {
              System.out.println("DFA for "+_fuir.clazzAsString(cl)+"("+_fuir.clazzArgCount(cl)+" args) at "+c+"."+i+": "+_fuir.codeAtAsString(cl,c,i)+": "+subv+" case "+mc+": "+
                                 (taken ? "taken" : "not taken"));
            }

          if (taken)
            {
              var field = _fuir.matchCaseField(cl, c, i, mc);
              if (field != -1)
                {
                  if (untagged != null)
                    {
                      _call._instance.setField(field, untagged);
                    }
                }
              var resv = ai.process(cl, _fuir.matchCaseCode(c, i, mc));
              if (resv._v0 != null)
                { // if at least one case returns (i.e., result is not null), this match returns.
                  r = Value.UNIT;
                }
            }
        }
      return new Pair(r, _unit_);
    }


    /**
     * Create a tagged value of type newcl from an untagged value for type valuecl.
     */
    public Pair<Value, Unit> tag(int cl, int valuecl, Value value, int newcl, int tagNum)
    {
      Value res = value.tag(_call._dfa, newcl, tagNum);
      return new Pair<>(res, _unit_);
    }


    /**
     * Access the effect of type ecl that is installed in the environemnt.
     */
    public Pair<Value, Unit> env(int ecl)
    {
      return new Pair<>(_call.getEffect(ecl), _unit_);
    }


    /**
     * Process a contract of kind ck of clazz cl that results in bool value cc
     * (i.e., the contract fails if !cc).
     */
    public Unit contract(int cl, FUIR.ContractKind ck, Value cc)
    {
      System.err.println("NYI: DFA.contract");
      return _unit_;
      /*
      return Unit.iff(cc.field(_names.TAG_NAME).not(),
                        Unit.seq(Value.fprintfstderr("*** failed " + ck + " on call to '%s'\n",
                                                       Value.string(_fuir.clazzAsString(cl))),
                                   Value.exit(1)));
      */
    }

  }


  /*----------------------------  constants  ----------------------------*/


  /**
   * For debugging: dump stack when _chaned is set, for debugging when fix point
   * is not reached.
   */
  static boolean SHOW_STACK_ON_CHANGE = false;


  /**
   * NYI: move get verbose level from options.
   */
  static boolean VERBOSE = false;


  /**
   * singleton instance of Unit.
   */
  static Unit _unit_ = new Unit();


  /**
   * DFA's intrinsics.
   */
  static TreeMap<String, IntrinsicDFA> _intrinsics_ = new TreeMap<>();


  /*-------------------------  static methods  --------------------------*/


  /**
   * Helper method to add intrinsic to _intrinsics_.
   */
  private static void put(String n, IntrinsicDFA c)
  {
    _intrinsics_.put(n, c);
  }


  /**
   * Get the names of all intrinsics supported by this backend.
   */
  public static Set<String> supportedIntrinsics()
  {
    return _intrinsics_.keySet();
  }


  /*----------------------------  variables  ----------------------------*/


  /**
   * The intermediate code we are analysing.
   */
  public final FUIR _fuir;


  /**
   * Instances created during DFA analysis.
   */
  TreeMap<Instance, Instance> _instances = new TreeMap<>();


  /**
   * Calls created during DFA analysis.
   */
  TreeMap<Call, Call> _calls = new TreeMap<>();


  /**
   * Map from type to corresponding default effects.
   *
   * NYI: this might need to be thread-local and not global!
   */
  TreeMap<Integer, Value> _defaultEffects = new TreeMap<>();


  /**
   * Flag to detect changes during current iteration of the fix-point algorithm.
   * If this remains false during one iteration we have reached a fix-point.
   */
  boolean _changed = false;
  String _changedSetBy;


  /**
   * Flag to control output of errors.  This is set to true after a fix point
   * has been reached to report errors that should disappear when fix point is
   * reached (like vars are initialized).
   */
  boolean _reportResults = false;


  /*---------------------------  consructors  ---------------------------*/


  /**
   * Create DFA for given intermediate code.
   *
   * @param fuir the intermediate code.
   */
  public DFA(FUIR fuir)
  {
    _fuir = fuir;
    Errors.showAndExit();
  }


  /*-----------------------------  methods  -----------------------------*/


  /**
   * Perform DFA analysis
   */
  public void dfa()
  {
    var callMain = newCall(_fuir.mainClazzId(),
                           false /* NYI: main's precondition is not analyzed */,
                           Value.UNIT,
                           new List<>(),
                           null /* env */,
                           () -> { System.out.println("program entry point"); return "  "; });
    findFixPoint();
    Errors.showAndExit();
  }


  /**
   * Iteratively perform data flow analysis until a fix point is reached.
   */
  void findFixPoint()
  {
    int cnt = 0;
    do
      {
        cnt++;
        System.out.println("DFA iteration #"+cnt+": --------------------------------------------------" +
                           (!VERBOSE ? "" : _calls.size()+","+_instances.size()+"; "+_changedSetBy));
        _changed = false;
        _changedSetBy = "*** change not set ***";
        iteration();
      }
    while (_changed && (true || cnt < 100) || false && (cnt < 50));
    System.out.println("DFA done:");
    System.out.println("Instances: " + _instances.values());
    System.out.println("Calls: ");
    for (var c : _calls.values())
      System.out.println("  call: " + c);
    _reportResults = true;
    iteration();
  }


  /**
   * Perform one iteration of the analysis.
   */
  void iteration()
  {
    var s = _calls.values().toArray(new Call[_calls.size()]);
    for (var c : s)
      {
        if (_reportResults && VERBOSE)
          {
            System.out.println(("----------------"+c+
                                "----------------------------------------------------------------------------------------------------")
                               .substring(0,100));
            c.showWhy();
          }
        analyze(c);
      }
  }


  /**
   * analyze call to one instance
   */
  void analyze(Call c)
  {
    var cl = c._cc;
    var ck = _fuir.clazzKind(cl);
    switch (ck)
      {
      case Routine  : analyzeCall(c, false); break;
      }
    if (_fuir.clazzContract(cl, FUIR.ContractKind.Pre, 0) != -1)
      {
        analyzeCall(c, true);
      }
  }


  /**
   * Analyze code for given routine cl
   *
   * @param i the instance we are analyzing.
   *
   * @param pre true to analyze cl's precondition, false for cl itself.
   */
  void analyzeCall(Call c, boolean pre)
  {
    if (PRECONDITIONS) require
      (_fuir.clazzKind(c._cc) == FUIR.FeatureKind.Routine || pre);

    var i = c._instance;
    check
      (c._args.size() == _fuir.clazzArgCount(c._cc));
    for (var a = 0; a < c._args.size(); a++)
      {
        var af = _fuir.clazzArg(c._cc, a);
        var aa = c._args.get(a);
        i.setField(af, aa);
      }

    // copy outer ref argument to outer ref field:
    var or = _fuir.clazzOuterRef(c._cc);
    if (or != -1)
      {
        i.setField(or, c._target);
      }

    if (pre)
      {
        // NYI: preOrPostCondition(cl, FUIR.ContractKind.Pre);
      }
    else
      {
        var ai = new AbstractInterpreter(_fuir, new Analyze(c));
        var r = ai.process(c._cc, false);
        if (r._v0 != null)
          {
            c.returns();
          }
      }
  }


  static Value NYIintrinsicMissing(Call cl)
  {
    if (true || cl._dfa._reportResults)
      {
        var name = cl._dfa._fuir.clazzIntrinsicName(cl._cc);
        System.out.println("NYI: Support for intrinsic '" + name + "' missing, needed by");
        cl.showWhy();
      }
    return Value.UNDEFINED;
  }


  static
  {
    put("safety"                         , cl -> NYIintrinsicMissing(cl) );
    put("debug"                          , cl -> NYIintrinsicMissing(cl) );
    put("debugLevel"                     , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.std.args.count"          , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.std.args.get"            , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.std.exit"                , cl -> null );
    put("fuzion.std.out.write"           , cl -> Value.UNIT );
    put("fuzion.std.err.write"           , cl -> Value.UNIT );
    put("fuzion.std.out.flush"           , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.std.err.flush"           , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.stdin.nextByte"          , cl -> NYIintrinsicMissing(cl) );
    put("i8.prefix -°"                   , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i16.prefix -°"                  , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i32.prefix -°"                  , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i64.prefix -°"                  , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i8.infix -°"                    , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i16.infix -°"                   , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i32.infix -°"                   , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i64.infix -°"                   , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i8.infix +°"                    , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i16.infix +°"                   , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i32.infix +°"                   , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i64.infix +°"                   , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i8.infix *°"                    , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i16.infix *°"                   , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i32.infix *°"                   , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i64.infix *°"                   , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i8.div"                         , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i16.div"                        , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i32.div"                        , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i64.div"                        , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i8.mod"                         , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i16.mod"                        , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i32.mod"                        , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i64.mod"                        , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i8.infix <<"                    , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i16.infix <<"                   , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i32.infix <<"                   , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i64.infix <<"                   , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i8.infix >>"                    , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i16.infix >>"                   , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i32.infix >>"                   , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i64.infix >>"                   , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i8.infix &"                     , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i16.infix &"                    , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i32.infix &"                    , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i64.infix &"                    , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i8.infix |"                     , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i16.infix |"                    , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i32.infix |"                    , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i64.infix |"                    , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i8.infix ^"                     , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i16.infix ^"                    , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i32.infix ^"                    , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );
    put("i64.infix ^"                    , cl -> { return new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)); } );

    put("i8.infix =="                    , cl -> Value.BOOL );
    put("i16.infix =="                   , cl -> Value.BOOL );
    put("i32.infix =="                   , cl -> Value.BOOL );
    put("i64.infix =="                   , cl -> Value.BOOL );
    put("i8.infix !="                    , cl -> Value.BOOL );
    put("i16.infix !="                   , cl -> Value.BOOL );
    put("i32.infix !="                   , cl -> Value.BOOL );
    put("i64.infix !="                   , cl -> Value.BOOL );
    put("i8.infix >"                     , cl -> Value.BOOL );
    put("i16.infix >"                    , cl -> Value.BOOL );
    put("i32.infix >"                    , cl -> Value.BOOL );
    put("i64.infix >"                    , cl -> Value.BOOL );
    put("i8.infix >="                    , cl -> Value.BOOL );
    put("i16.infix >="                   , cl -> Value.BOOL );
    put("i32.infix >="                   , cl -> Value.BOOL );
    put("i64.infix >="                   , cl -> Value.BOOL );
    put("i8.infix <"                     , cl -> Value.BOOL );
    put("i16.infix <"                    , cl -> Value.BOOL );
    put("i32.infix <"                    , cl -> Value.BOOL );
    put("i64.infix <"                    , cl -> Value.BOOL );
    put("i8.infix <="                    , cl -> Value.BOOL );
    put("i16.infix <="                   , cl -> Value.BOOL );
    put("i32.infix <="                   , cl -> Value.BOOL );
    put("i64.infix <="                   , cl -> Value.BOOL );

    put("u8.prefix -°"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u16.prefix -°"                  , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u32.prefix -°"                  , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u64.prefix -°"                  , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u8.infix -°"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u16.infix -°"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u32.infix -°"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u64.infix -°"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u8.infix +°"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u16.infix +°"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u32.infix +°"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u64.infix +°"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u8.infix *°"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u16.infix *°"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u32.infix *°"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u64.infix *°"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u8.div"                         , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u16.div"                        , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u32.div"                        , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u64.div"                        , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u8.mod"                         , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u16.mod"                        , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u32.mod"                        , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u64.mod"                        , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u8.infix <<"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u16.infix <<"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u32.infix <<"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u64.infix <<"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u8.infix >>"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u16.infix >>"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u32.infix >>"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u64.infix >>"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u8.infix &"                     , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u16.infix &"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u32.infix &"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u64.infix &"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u8.infix |"                     , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u16.infix |"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u32.infix |"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u64.infix |"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u8.infix ^"                     , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u16.infix ^"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u32.infix ^"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u64.infix ^"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );

    put("u8.infix =="                    , cl -> Value.BOOL );
    put("u16.infix =="                   , cl -> Value.BOOL );
    put("u32.infix =="                   , cl -> Value.BOOL );
    put("u64.infix =="                   , cl -> Value.BOOL );
    put("u8.infix !="                    , cl -> Value.BOOL );
    put("u16.infix !="                   , cl -> Value.BOOL );
    put("u32.infix !="                   , cl -> Value.BOOL );
    put("u64.infix !="                   , cl -> Value.BOOL );
    put("u8.infix >"                     , cl -> Value.BOOL );
    put("u16.infix >"                    , cl -> Value.BOOL );
    put("u32.infix >"                    , cl -> Value.BOOL );
    put("u64.infix >"                    , cl -> Value.BOOL );
    put("u8.infix >="                    , cl -> Value.BOOL );
    put("u16.infix >="                   , cl -> Value.BOOL );
    put("u32.infix >="                   , cl -> Value.BOOL );
    put("u64.infix >="                   , cl -> Value.BOOL );
    put("u8.infix <"                     , cl -> Value.BOOL );
    put("u16.infix <"                    , cl -> Value.BOOL );
    put("u32.infix <"                    , cl -> Value.BOOL );
    put("u64.infix <"                    , cl -> Value.BOOL );
    put("u8.infix <="                    , cl -> Value.BOOL );
    put("u16.infix <="                   , cl -> Value.BOOL );
    put("u32.infix <="                   , cl -> Value.BOOL );
    put("u64.infix <="                   , cl -> Value.BOOL );

    put("i8.as_i32"                      , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("i16.as_i32"                     , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("i32.as_i64"                     , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("i32.as_f64"                     , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("i64.as_f64"                     , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u8.as_i32"                      , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u16.as_i32"                     , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u32.as_i64"                     , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u32.as_f64"                     , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u64.as_f64"                     , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("i8.castTo_u8"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("i16.castTo_u16"                 , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("i32.castTo_u32"                 , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("i64.castTo_u64"                 , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u8.castTo_i8"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u16.castTo_i16"                 , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u32.castTo_i32"                 , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u32.castTo_f32"                 , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u64.castTo_i64"                 , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u64.castTo_f64"                 , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u16.low8bits"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u32.low8bits"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u64.low8bits"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u32.low16bits"                  , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u64.low16bits"                  , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("u64.low32bits"                  , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );

    put("f32.prefix -"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64.prefix -"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32.infix +"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64.infix +"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32.infix -"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64.infix -"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32.infix *"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64.infix *"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32.infix /"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64.infix /"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32.infix %"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64.infix %"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32.infix **"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64.infix **"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32.infix =="                   , cl -> Value.BOOL );
    put("f64.infix =="                   , cl -> Value.BOOL );
    put("f32.infix !="                   , cl -> Value.BOOL );
    put("f64.infix !="                   , cl -> Value.BOOL );
    put("f32.infix <"                    , cl -> Value.BOOL );
    put("f64.infix <"                    , cl -> Value.BOOL );
    put("f32.infix <="                   , cl -> Value.BOOL );
    put("f64.infix <="                   , cl -> Value.BOOL );
    put("f32.infix >"                    , cl -> Value.BOOL );
    put("f64.infix >"                    , cl -> Value.BOOL );
    put("f32.infix >="                   , cl -> Value.BOOL );
    put("f64.infix >="                   , cl -> Value.BOOL );
    put("f32.as_f64"                     , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64.as_f32"                     , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64.as_i64_lax"                 , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32.castTo_u32"                 , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64.castTo_u64"                 , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32.asString"                   , cl -> NYIintrinsicMissing(cl) );
    put("f64.asString"                   , cl -> NYIintrinsicMissing(cl) );

    put("f32s.minExp"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32s.maxExp"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32s.minPositive"               , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32s.max"                       , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32s.epsilon"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32s.isNaN"                     , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64s.isNaN"                     , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64s.minExp"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64s.maxExp"                    , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64s.minPositive"               , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64s.max"                       , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64s.epsilon"                   , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32s.squareRoot"                , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64s.squareRoot"                , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32s.exp"                       , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64s.exp"                       , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32s.log"                       , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64s.log"                       , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32s.sin"                       , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64s.sin"                       , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32s.cos"                       , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64s.cos"                       , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32s.tan"                       , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64s.tan"                       , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32s.asin"                      , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64s.asin"                      , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32s.acos"                      , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64s.acos"                      , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32s.atan"                      , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64s.atan"                      , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32s.atan2"                     , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64s.atan2"                     , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32s.sinh"                      , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64s.sinh"                      , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32s.cosh"                      , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64s.cosh"                      , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f32s.tanh"                      , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );
    put("f64s.tanh"                      , cl -> new NumericValue(cl._dfa, cl._dfa._fuir.clazzResultClazz(cl._cc)) );

    put("Object.hashCode"                , cl -> NYIintrinsicMissing(cl) );
    put("Object.asString"                , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.sys.array.alloc"         , cl -> { return new SysArray(cl._dfa, new byte[0]); } ); // NYI: get length from args
    put("fuzion.sys.array.setel"         , cl ->
        { /* NYI: record array modification */
          var array = cl._args.get(0);
          var index = cl._args.get(1);
          var value = cl._args.get(2);
          if (array instanceof SysArray sa)
            {
              sa.setel(index, value);
              return Value.UNIT;
            }
          else
            {
              throw new Error("intrinsic fuzion.sys.array.setel: Expected class SysArray, found "+array.getClass()+" "+array);
            }
        });
    put("fuzion.sys.array.get"           , cl ->
        { /* NYI: record array modification */
          var array = cl._args.get(0);
          var index = cl._args.get(1);
          if (array instanceof SysArray sa)
            {
              return sa.get(index);
            }
          else
            {
              throw new Error("intrinsic fuzion.sys.array.gel: Expected class SysArray, found "+array.getClass()+" "+array);
            }
        });
    put("fuzion.sys.env_vars.has0"       , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.sys.env_vars.get0"       , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.sys.thread.spawn0"       , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.std.nano_sleep"          , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.std.nano_time"           , cl -> NYIintrinsicMissing(cl) );

    put("effect.replace"                 , cl ->
        {
          var ecl = cl._dfa._fuir.effectType(cl._cc);
          var new_e = cl._target;
          cl.replaceEffect(ecl, new_e);
          return Value.UNIT;
        });
    put("effect.default"                 , cl ->
        {
          var ecl = cl._dfa._fuir.effectType(cl._cc);
          var oc = cl._dfa._fuir.clazzOuterClazz(cl._cc);
          var new_e = cl._target;
          var old_e = cl._dfa._defaultEffects.get(ecl);
          if (old_e != null)
            {
              new_e = old_e.join(new_e);
            }
          if (old_e == null || Value.compare(old_e, new_e) != 0)
            {
              cl._dfa._defaultEffects.put(ecl, new_e);
              if (!cl._dfa._changed)
                {
                  cl._dfa._changedSetBy = "effect.defaut called: "+cl._dfa._fuir.clazzAsString(cl._cc);
                }
              cl._dfa._changed = true;
            }
          return Value.UNIT;
        });
    put("effect.abortable"               , cl ->
        {
          var ecl = cl._dfa._fuir.effectType(cl._cc);
          var oc = cl._dfa._fuir.clazzActualGeneric(cl._cc, 0);
          var call = cl._dfa._fuir.lookupCall(oc);
          System.err.println("**** DFA handling for effect.abortable missing");

          if (CHECKS) check
            (cl._dfa._fuir.clazzNeedsCode(call));

          var env = cl._env;
          if (cl._target == null)
            {
              System.out.println("for effect.abortable: "+cl+" target is "+cl._target);
            }
          var newEnv = new Env(cl._dfa, env, ecl, cl._target);
          var ncl = cl._dfa.newCall(call, false, cl._args.get(0), new List<>(), newEnv, cl);
          return Value.UNIT;
        });
    put("effect.abort"                   , cl -> NYIintrinsicMissing(cl) );
    put("effects.exists"                 , cl -> cl.getEffect(cl._dfa._fuir.clazzActualGeneric(cl._cc, 0)) != null
        ? Value.TRUE
        : Value.BOOL  /* NYI: currently, this is never FALSE since a default effect might get installed turning this into TRUE
                       * should reconsider if handling of default effects changes
                       */
        );
    put("fuzion.java.JavaObject.isNull"  , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.java.arrayGet"           , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.java.arrayLength"        , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.java.arrayToJavaObject0" , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.java.boolToJavaObject"   , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.java.callC0"             , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.java.callS0"             , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.java.callV0"             , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.java.f32ToJavaObject"    , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.java.f64ToJavaObject"    , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.java.getField0"          , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.java.getStaticField0"    , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.java.i16ToJavaObject"    , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.java.i32ToJavaObject"    , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.java.i64ToJavaObject"    , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.java.i8ToJavaObject"     , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.java.javaStringToString" , cl -> NYIintrinsicMissing(cl) );
    put("fuzion.java.stringToJavaObject0", cl -> NYIintrinsicMissing(cl) );
    put("fuzion.java.u16ToJavaObject"    , cl -> NYIintrinsicMissing(cl) );
  }


  /**
   * Add given value to the set of default effect values for effect type ecl.
   */
  void replaceDefaultEffect(int ecl, Value e)
  {
    var oe = _defaultEffects.get(ecl);
    if (oe == null)
      {
        throw new Error("replaceDefaultEffect called when there is no default effect!");
      }
    var ne = e.join(oe);
    if (Value.compare(oe, ne) != 0)
      {
        _defaultEffects.put(ecl, ne);
        if (!_changed)
          {
            _changedSetBy = "effect.replace called: " + _fuir.clazzAsString(ecl);
          }
        _changed = true;
      }
  }


  /**
   * Create instance of given clazz.
   *
   * @param cl the clazz
   *
   * @param context for debugging: Reason that causes this instance to be part
   * of the analysis.
   */
  Value newInstance(int cl, Context context)
  {
    return switch (_fuir.getSpecialId(cl))
      {
        case c_i8   ,
          c_i16  ,
             c_i32  ,
             c_i64  ,
             c_u8   ,
             c_u16  ,
             c_u32  ,
             c_u64  ,
             c_f32  ,
             c_f64  -> new NumericValue(DFA.this, cl);
        default ->
        {
          var r = new Instance(this, cl, context);
          var e = _instances.get(r);
          if (e == null)
            {
              _instances.put(r, r);
              e = r;
              if (SHOW_STACK_ON_CHANGE && !_changed) Thread.dumpStack();
              if (!_changed)
                {
                  _changedSetBy = "DFA.newInstance for "+_fuir.clazzAsString(cl);
                }
              _changed = true;
            }
          yield e;
        }
      };
  }


  /**
   * Create constnat string with given utf8 bytes.
   *
   * @param utf8Bytes the string contents
   *
   * @param context for debugging: Reason that causes this const string to be
   * part of the analysis.
   */
  Value newConstString(byte[] utf8Bytes, Context context)
  {
    if (PRECONDITIONS) require
      (utf8Bytes != null);

    var cs            = _fuir.clazz_conststring();
    var internalArray = _fuir.clazz_conststring_internalArray();
    var data          = _fuir.clazz_fuzionSysArray_u8_data();
    var length        = _fuir.clazz_fuzionSysArray_u8_length();
    var sysArray      = _fuir.clazzResultClazz(internalArray);
    var adata = new SysArray(this, utf8Bytes);
    var r = newInstance(cs, context);
    var a = newInstance(sysArray, context);
    a.setField(length, new NumericValue(this, _fuir.clazzResultClazz(length), utf8Bytes.length));
    a.setField(data  , adata);
    r.setField(internalArray, a);
    return r;
  }


  /**
   * Create call to given clazz with given target and args.
   *
   * @param cl the called clazz
   *
   * @param pre true iff precondition is called
   *
   * @param tvalue the target value on which cl is called
   *
   * @param args the arguments passed to the call
   *
   * @param context for debugging: Reason that causes this call to be part of
   * the analysis.
   */
  Call newCall(int cl, boolean pre, Value tvalue, List<Value> args, Env env, Context context)
  {
    var r = new Call(this, cl, pre, tvalue, args, env, context);
    var e = _calls.get(r);
    if (e == null)
      {
        _calls.put(r,r);
        e = r;
        if (SHOW_STACK_ON_CHANGE && !_changed) { System.out.println("new call: "+r); Thread.dumpStack();}
        if (!_changed)
          {
            _changedSetBy = "DFA.newCall to "+e;
          }
        _changed = true;
      }
    return e;
  }

  Call existingU32 = null;

}

/* end of file */
