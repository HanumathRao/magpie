package com.stuffwithstuff.magpie.interpreter;

import java.util.*;

import com.stuffwithstuff.magpie.ast.*;

public class Interpreter {
  public Interpreter(InterpreterHost host) {
    mHost = host;
    
    // Create a top-level scope.
    mGlobalScope = new Scope();
    
    mClassClass = new ClassObj();
    
    mBoolClass = new ClassObj(mClassClass);
    mBoolClass.addInstanceMethod("not", new NativeMethod.BoolNot());
    mBoolClass.addInstanceMethod("toString", new NativeMethod.BoolToString());

    mDynamicClass = new ClassObj(mClassClass);
    
    mFnClass = new ClassObj(mClassClass);
    mFnClass.addInstanceMethod("apply", new NativeMethod.FunctionApply());
    
    mIntClass = new ClassObj(mClassClass);
    mIntClass.addInstanceMethod("+", new NativeMethod.IntPlus());
    mIntClass.addInstanceMethod("-", new NativeMethod.IntMinus());
    mIntClass.addInstanceMethod("*", new NativeMethod.IntMultiply());
    mIntClass.addInstanceMethod("/", new NativeMethod.IntDivide());
    mIntClass.addInstanceMethod("toString", new NativeMethod.IntToString());
    mIntClass.addInstanceMethod("==", new NativeMethod.IntEqual());
    mIntClass.addInstanceMethod("!=", new NativeMethod.IntNotEqual());
    mIntClass.addInstanceMethod("<",  new NativeMethod.IntLessThan());
    mIntClass.addInstanceMethod(">",  new NativeMethod.IntGreaterThan());
    mIntClass.addInstanceMethod("<=", new NativeMethod.IntLessThanOrEqual());
    mIntClass.addInstanceMethod(">=", new NativeMethod.IntGreaterThanOrEqual());

    mStringClass = new ClassObj(mClassClass);
    mStringClass.addInstanceMethod("+",     new NativeMethod.StringPlus());
    mStringClass.addInstanceMethod("print", new NativeMethod.StringPrint());

    // TODO(bob): At some point, may want different tuple types based on the
    // types of the fields.
    mTupleClass = new ClassObj(mClassClass);
    mTupleClass.addInstanceMethod("apply", new NativeMethod.TupleGetField());
    mTupleClass.addInstanceMethod("count", new NativeMethod.ClassFieldGetter("count",
        new NameExpr("Int")));
    
    // TODO(bob): Hackish.
    for (int i = 0; i < 20; i++) {
      String name = Integer.toString(i);
      // TODO(bob): Using dynamic as the type here is lame. Ideally, there would
      // be a separate tuple class for each set of tuple field types and it
      // would have field getters that were typed to match the fields.
      mTupleClass.addInstanceMethod(name, new NativeMethod.ClassFieldGetter(name,
          new NameExpr("Dynamic")));
    }
    
    mNothingClass = new ClassObj(mClassClass);
    mNothingClass.addInstanceMethod("toString", new NativeMethod.NothingToString());
    mNothing = new Obj(mNothingClass);
    
    // Give the classes names and make then available.
    mGlobalScope.define("Bool", mBoolClass);
    mGlobalScope.define("Function", mFnClass);
    mGlobalScope.define("Dynamic", mDynamicClass);
    mGlobalScope.define("Int", mIntClass);
    mGlobalScope.define("Nothing", mNothingClass);
    mGlobalScope.define("String", mStringClass);
    mGlobalScope.define("Tuple", mTupleClass);

    mBoolClass.setField("name", createString("Bool"));
    mClassClass.setField("name", createString("Class"));
    mDynamicClass.setField("name", createString("Dynamic"));
    mFnClass.setField("name", createString("Function"));
    mIntClass.setField("name", createString("Int"));
    mNothingClass.setField("name", createString("Nothing"));
    mStringClass.setField("name", createString("String"));
    mTupleClass.setField("name", createString("Tuple"));
  }
  
  public void load(List<Expr> expressions) {
    EvalContext context = createTopLevelContext();
    
    // Evaluate the expressions. This is the load time evaluation.
    for (Expr expr : expressions) {
      evaluate(expr, context);
    }
  }
  
  public List<CheckError> check() {
    List<CheckError> errors = new ArrayList<CheckError>();

    ExprChecker.check(this, errors, mGlobalScope);
    
    return errors;
  }
  
  public void runMain() {
    EvalContext context = createTopLevelContext();
    Obj main = context.lookUp("main");
    if (main == null) return;
    
    if (!(main instanceof Invokable)) {
      throw new InterpreterException("Member \"main\" is not a function.");
    }
    
    Invokable mainFn = (Invokable)main;
    mainFn.invoke(this, mNothing, mNothing);
  }
  
  public void print(String text) {
    mHost.print(text);
  }
  
  public void runtimeError(Expr expr, String format, Object... args) {
    mHost.runtimeError(expr.getPosition(), String.format(format, args));
  }
  
  public EvalContext createTopLevelContext() {
    return new EvalContext(mGlobalScope, mNothing);
  }
  
  public Scope getGlobals() { return mGlobalScope; }
  
  /**
   * Gets the single value () of type Nothing.
   * @return
   */
  public Obj nothing() { return mNothing; }

  public ClassObj getBoolType() { return mBoolClass; }
  public ClassObj getDynamicType() { return mDynamicClass; }
  public ClassObj getIntType() { return mIntClass; }
  public ClassObj getNothingType() { return mNothingClass; }
  public ClassObj getStringType() { return mStringClass; }
  
  public Obj createBool(boolean value) {
    return mBoolClass.instantiate(value);
  }

  public Obj createInt(int value) {
    return mIntClass.instantiate(value);
  }
  
  public Obj createString(String value) {
    return mStringClass.instantiate(value);
  }
  
  public ClassObj createClass() {
    return new ClassObj(mClassClass);
  }
  
  public FnObj createFn(FnExpr expr) {
    return new FnObj(mFnClass, expr);
  }
  
  public Obj createTuple(EvalContext context, Obj... fields) {
    // A tuple is an object with fields whose names are zero-based numbers.
    Obj tuple = mTupleClass.instantiate();
    for (int i = 0; i < fields.length; i++) {
      String name = Integer.toString(i);
      tuple.setField(name, fields[i]);
    }
    
    tuple.setField("count", createInt(fields.length));
    
    return tuple;
  }
  
  public Obj evaluate(Expr expr, EvalContext context) {
    ExprEvaluator evaluator = new ExprEvaluator(this);
    return evaluator.evaluate(expr, context);
  }
  
  private final InterpreterHost mHost;
  private Scope mGlobalScope;
  private final ClassObj mClassClass;
  private final ClassObj mBoolClass;
  private final ClassObj mDynamicClass;
  private final ClassObj mFnClass;
  private final ClassObj mIntClass;
  private final ClassObj mNothingClass;
  private final ClassObj mStringClass;
  private final ClassObj mTupleClass;
  
  private final Obj mNothing;
}