package com.stuffwithstuff.magpie;

import java.util.*;
import com.stuffwithstuff.magpie.ast.*;

public class MagpieParser extends Parser {
  public MagpieParser(Lexer lexer) {
    super(lexer);
  }
  
  public List<Expr> parse() {
    // Parse the entire file.
    List<Expr> expressions = new ArrayList<Expr>();
    do {
      expressions.add(expression());
      
      // Allow files with no trailing newline.
      if (match(TokenType.EOF)) break;
      
      consume(TokenType.LINE);
    } while (!match(TokenType.EOF));

    return expressions;
  }
  
  private Expr expression() {
    return definition();
  }
  
  private Expr definition() {
    if (match(TokenType.VAR)) {
      Position startPos = last(1).getPosition();
      
      // TODO(bob): support multiple definitions and tuple decomposition here
      String name = consume(TokenType.NAME).getString();
      
      // See if we're defining a function in shorthand notation:
      // def foo() blah
      if (lookAhead(TokenType.LEFT_PAREN)) {
        Position fnPosition = last(1).getPosition();
        
        List<String> paramNames = new ArrayList<String>();
        FunctionType type = functionType(paramNames);
        Expr body = parseBlock();
        
        // Desugar it to: def foo = fn () blah
        FnExpr function = new FnExpr(Position.union(fnPosition, body.getPosition()),
            paramNames, type.getParamType(), type.getReturnType(), body);
        return new DefineExpr(Position.union(startPos, function.getPosition()),
            name, function);
      } else {
        // Just a regular variable definition.
        consume(TokenType.EQUALS);
        
        Expr value = flowControl();
        return new DefineExpr(Position.union(startPos, value.getPosition()),
            name, value);
      }
    }
    else if (lookAheadAny(TokenType.CLASS, TokenType.EXTEND)) {
      return parseClass();
    } else return flowControl();
  }
    
  private Expr flowControl() {
    if (match(TokenType.DO)) {
      // "do" block.
      return parseBlock();
    } else if (lookAheadAny(TokenType.IF, TokenType.LET)) {
      Position startPos = current().getPosition();
      
      // Let expressions and multiple if conditions get desugared like this:
      //
      // let a = foo
      // let b = bar
      // if c = d then
      //     e
      // end
      //
      // To:
      //
      // var a__ = foo
      // if a__ != () then
      //     var a = a__ // plus some type annotation to remove ()
      //     var b__ = bar
      //     if b__ != () then
      //         var b = b__
      //         if c = d then
      //             e
      //         end
      //     end
      // end
      //
      
      // Parse the conditions.
      Stack<Condition> conditions = new Stack<Condition>();
      while (true) {
        if (match(TokenType.IF)) {
          conditions.add(new Condition(parseIfBlock()));
        } else if (match(TokenType.LET)) {
          // TODO(bob): Eventually allow tuple decomposition here.
          String name = consume(TokenType.NAME).getString();
          consume(TokenType.EQUALS);
          conditions.add(new Condition(name, parseIfBlock()));
        } else {
          break;
        }
      }
      
      // Parse the then body.
      consume(TokenType.THEN);
      Expr thenExpr = parseThenBlock();
      
      // Parse the else body.
      Expr elseExpr = null;
      if (match(TokenType.ELSE) || match(TokenType.LINE, TokenType.ELSE)) {
        elseExpr = parseElseBlock();
      } else {
        elseExpr = new NothingExpr(last(1).getPosition());
      }
      
      return new IfExpr(Position.union(startPos, elseExpr.getPosition()),
          conditions, thenExpr, elseExpr);
    } else if (lookAheadAny(TokenType.WHILE, TokenType.FOR)) {
      // "while" and "for" loop.
      Position startPos = current().getPosition();
      
      // A for loop is desugared from this:
      //
      //   for a <- foo do
      //     print a
      //   end
      //
      // To:
      //
      //   do
      //     def __a_gen = foo.generate
      //     while __a_gen.next do
      //       def a = __a_gen.current
      //       print a
      //     end
      //   end
      
      List<Expr> generators = new ArrayList<Expr>();
      List<Expr> initializers = new ArrayList<Expr>();
      
      List<Expr> conditions = new ArrayList<Expr>();
      
      while (match(TokenType.WHILE) || match(TokenType.FOR)) {
        if (last(1).getType() == TokenType.WHILE) {
          conditions.add(expression());
        } else {
          Token nameToken = consume(TokenType.NAME);
          String variable = nameToken.getString();
          Position position = nameToken.getPosition();
          consume(TokenType.EQUALS);
          Expr generator = expression();
          
          // Initialize the generator before the loop.
          String generatorVar = variable + " gen";
          generators.add(new DefineExpr(position, generatorVar,
              new MethodExpr(generator, "generate", new NothingExpr(position))));
          
          // The the condition expression just increments the generator.
          conditions.add(new MethodExpr(
              new NameExpr(position, generatorVar), "next", new NothingExpr(position)));
          
          // In the body of the loop, we need to initialize the variable.
          initializers.add(new DefineExpr(position, variable,
              new MethodExpr(new NameExpr(position, generatorVar), "current", new NothingExpr(position))));
        }
        match(TokenType.LINE); // Optional line after a clause.
      }
      
      consume(TokenType.DO);
      Expr body = parseBlock();
      
      // If there are "for" loops, mix in the generators and variables.
      if (generators.size() > 0) {
        // Create the variables inside the loop.
        List<Expr> innerBlock = new ArrayList<Expr>();
        for (Expr initializer : initializers) innerBlock.add(initializer);

        // Then execute the main body.
        innerBlock.add(body);
        body = new BlockExpr(innerBlock);
        
        // Create the generators outside the loop.
        List<Expr> outerBlock = new ArrayList<Expr>();
        for (Expr generator : generators) outerBlock.add(generator);
        
        // Then execute the loop.
        outerBlock.add(new LoopExpr(Position.union(startPos, body.getPosition()), conditions, body));
        return new BlockExpr(outerBlock);
      }
      
      return new LoopExpr(Position.union(startPos, body.getPosition()), conditions, body);
    }
    else return assignment();
  }
  
  private Expr assignment() {
    Expr expr = tuple();
    
    if (match(TokenType.EQUALS)) {
      // Parse the value being assigned.
      Expr value = flowControl();

      Position position = Position.union(expr.getPosition(), value.getPosition());
      
      // Transform the left-hand expression into an assignment form. Examples:
      // a = v       ->  a = v      AssignExpr(null, "a", null, v)
      // a.b = v     ->  a.b=(v)    AssignExpr(a,    "b", null, v)
      // a.b.c = v   ->  a.b.c=(v)  AssignExpr(a.b,  "c", null, v)
      // a.b c = v   ->  a.b=(c, v) AssignExpr(a,    "b", c,    v)
      // a ^ b = v   ->  a.^=(b)    AssignExpr(a,    "^", b,    v)
      // Other expression forms on the left-hand side are considered invalid and
      // will throw a parse exception.
      // TODO(bob): Need to handle tuples here too.
      
      if (expr instanceof NameExpr) {
        return new AssignExpr(position, null, ((NameExpr)expr).getName(), null, value);
      } else if (expr instanceof MethodExpr) {
        MethodExpr method = (MethodExpr) expr;
        
        if (method.getArg() instanceof NothingExpr) {
          // a.b = v -> a.b=(v)
          return new AssignExpr(position, method.getReceiver(), method.getMethod(), null, value);
        } else {
          // a.b c = v -> a.b=(c, v)
          return new AssignExpr(position, method.getReceiver(), method.getMethod(),
              method.getArg(), value);
        }
      } else {
        throw new ParseException("Expression \"" + expr +
        "\" is not a valid target for assignment.");
      }
    }
    
    return expr;
  }
  
  // TODO(bob): There's a lot of overlap in the next four functions, but,
  //            unfortunately also some slight differences. It would be cool to
  //            unify these somehow.
  
  private Expr parseBlock() {
    if (match(TokenType.LINE)){
      List<Expr> exprs = new ArrayList<Expr>();
      
      while (!match(TokenType.END)) {
        exprs.add(expression());
        consume(TokenType.LINE);
      }
            
      return new BlockExpr(exprs);
    } else {
      return expression();
    }
  }

  private Expr parseIfBlock() {
    if (match(TokenType.LINE)){
      List<Expr> exprs = new ArrayList<Expr>();
      
      do {
        exprs.add(expression());
        consume(TokenType.LINE);
      } while (!lookAhead(TokenType.THEN));
      
      match(TokenType.LINE);

      return new BlockExpr(exprs);
    } else {
      Expr expr = expression();
      // Each if expression may be on its own line.
      match(TokenType.LINE);
      return expr;
    }
  }

  private Expr parseThenBlock() {
    if (match(TokenType.LINE)){
      List<Expr> exprs = new ArrayList<Expr>();
      
      do {
        exprs.add(expression());
        consume(TokenType.LINE);
      } while (!lookAhead(TokenType.ELSE) && !match(TokenType.END));
      
      return new BlockExpr(exprs);
    } else {
      return expression();
    }
  }
  
  private Expr parseElseBlock() {
    if (match(TokenType.LINE)){
      List<Expr> exprs = new ArrayList<Expr>();
      
      do {
        exprs.add(expression());
        consume(TokenType.LINE);
      } while (!match(TokenType.END));
      
      return new BlockExpr(exprs);
    } else {
      return expression();
    }
  }
  
  /**
   * Parses a tuple expression like "a, b, c".
   */
  private Expr tuple() {
    List<Expr> fields = new ArrayList<Expr>();
    
    do {
      fields.add(operator());
    } while (match(TokenType.COMMA));
    
    // Only wrap in a tuple if there are multiple fields.
    if (fields.size() == 1) return fields.get(0);
    
    return new TupleExpr(fields);
  }

  /**
   * Parses a series of operator expressions like "a + b - c".
   */
  private Expr operator() {
    Expr left = call();
    if (left == null) {
      throw new ParseException(":(");
    }
    
    while (match(TokenType.OPERATOR)) {
      String op = last(1).getString();
      Expr right = call();
      if (right == null) {
        throw new ParseException(":(");
      }

      left = new MethodExpr(left, op, right);
    }
    
    return left;
  }

  // The next two functions are a bit squirrely. Function calls like "abs 123"
  // are generally lower precedence than method calls like "123.abs". However,
  // they interact with each other. Some examples will clarify:
  // a b c d  ->  a(b(c(d)))
  // a b c.d  ->  a(b(c.d())
  // a b.c d  ->  a(b.c(d))
  // a b.c.d  ->  a(b.c().d())
  // a.b c d  ->  a.b(c(d))
  // a.b c.d  ->  a.b(c.d())
  // a.b.c d  ->  a.b().c(d)
  // a.b.c.d  ->  a.b().c().d()
  
  /**
   * Parses a series of function calls like "foo bar bang".
   * @return The parsed expression or null if unsuccessful.
   */
  private Expr call() {
    Expr expr = method();
    if (expr == null) return null;
    
    Expr arg = call();
    if (arg == null) return expr;
    
    return new CallExpr(expr, arg);
  }
  
  /**
   * Parses a series of method calls like "foo.bar.bang".
   * @return The parsed expression or null if unsuccessful.
   */
  private Expr method() {
    Expr receiver = primary();
    if (receiver == null) return null;
    
    while (match(TokenType.DOT)) {
      if (match(TokenType.NAME)) {
        // Regular named method: foo.bar
        String method = last(1).getString();

        Expr arg = call();
        if (arg == null) {
          // If the argument is omitted, infer ()
          arg = new NothingExpr(last(1).getPosition());
        }
        receiver = new MethodExpr(receiver, method, arg);
      } else {
        // Functor object: foo.123
        Expr arg = primary();
        receiver = new MethodExpr(receiver, "apply", arg);
      }
    }
    
    return receiver;
  }
  
  /**
   * Parses a primary expression like a literal.
   * @return The parsed expression or null if unsuccessful.
   */
  private Expr primary() {
    if (match(TokenType.BOOL)){
    return new BoolExpr(last(1));
    } else if (match(TokenType.INT)) {
      return new IntExpr(last(1));
    } else if (match(TokenType.STRING)) {
      return new StringExpr(last(1));
    } else if (match(TokenType.NAME)) {
      return new NameExpr(last(1));
    } else if (match(TokenType.THIS)) {
      return new ThisExpr(last(1).getPosition());
    } else if (match(TokenType.FN)) {
      return parseFunction();
    } else if (match(TokenType.LEFT_PAREN, TokenType.RIGHT_PAREN)) {
      return new NothingExpr(
          Position.union(last(2).getPosition(), last(1).getPosition()));
    } else if (match(TokenType.LEFT_PAREN)) {
      Expr expr = expression();
      consume(TokenType.RIGHT_PAREN);
      return expr;
    }
    
    return null;
  }
  
  private Expr parseClass() {
    // Parse the class name line.
    boolean isExtend = match(TokenType.EXTEND);
    if (!isExtend) consume(TokenType.CLASS);
    
    String name = consume(TokenType.NAME).getString();
    Position position = last(1).getPosition();
    
    consume(TokenType.LINE);
    
    ClassExpr classExpr = new ClassExpr(position, isExtend, name);
    
    // There are four kinds of things that can appear in a class body:
    // class Foo
    //     // 1. constructors
    //     this (bar Bar) ...
    //
    //     // 2. field declarations
    //     x Int
    //
    //     // 3. field definitions
    //     y = 123
    //
    //     // 4. method definitions
    //     doSomething (a Int) ...
    //     + (other) ...
    //
    //     // 5. shared field declarations
    //     shared x Int
    //
    //     // 6. shared field definitions
    //     shared y = 123
    //
    //     // 7. shared method definitions
    //     shared doSomething (a Int) ...
    // end
    
    // Parse the body.
    while (!match(TokenType.END)) {
      if (match(TokenType.THIS)) {
        // Constructor.
        FnExpr body = parseFunction();
        classExpr.defineConstructor(body);
      } else {
        // Member declaration.
        boolean isShared = match(TokenType.SHARED);
        String member = consumeAny(TokenType.NAME, TokenType.OPERATOR)
            .getString();
        
        // See what kind of member it is.
        if (match(TokenType.EQUALS)) {
          // Field definition: "a = 123".
          Expr body = parseBlock();
          classExpr.defineField(isShared, member, body);
        }
        else if (lookAhead(TokenType.LEFT_PAREN)) {
          // Method definition: "foo () print 123".
          FnExpr function = parseFunction();
          classExpr.defineMethod(isShared, member, function);
        } else {
          // Field declaration.
          if (isShared) throw new ParseException(
              "Field declarations cannot be shared.");
          
          Expr type = typeDeclaration();
          classExpr.declareField(member, type);
        }
      }
      consume(TokenType.LINE);
    }
    
    return classExpr;
  }

  // fn (a) print "hi"
  private FnExpr parseFunction() {
    List<String> paramNames = new ArrayList<String>();
    FunctionType type = functionType(paramNames);
    
    Expr body = parseBlock();
    
    return new FnExpr(body.getPosition(), paramNames, type.getParamType(),
        type.getReturnType(), body);
  }

  /**
   * Parses a function type declaration. Valid examples include:
   * (->)           // takes nothing, returns nothing
   * (a)            // takes a single dynamic, returns dynamic
   * (a ->)         // takes a single dynamic, returns nothing
   * ()             // takes dynamic, returns a dynamic
   * (a Int -> Int) // takes and returns an int
   * 
   * @param paramNames After calling, will contain the list of parameter names.
   *                   If this is null, no parameter names will be parsed.
   *                   (This is used for inner function type declarations like
   *                   fn (Int, String ->).)
   * @return The parsed function type.
   */
  private FunctionType functionType(List<String> paramNames) {
    // Parse the prototype: (foo Foo, bar Bar -> Bang)
    consume(TokenType.LEFT_PAREN);
    
    // Parse the parameters, if any.
    List<Expr> paramTypes = new ArrayList<Expr>();
    while (!lookAheadAny(TokenType.ARROW, TokenType.RIGHT_PAREN)){
      if (paramNames != null) {
        paramNames.add(consume(TokenType.NAME).getString());
      }
      
      // TODO(bob): Need to handle named parameter with no type as a dynamic
      // parameter.
      paramTypes.add(typeDeclaration());
      
      if (!match(TokenType.COMMA)) break;
    }
    
    // Aggregate the parameter types into a single type.
    Expr paramType = null;
    switch (paramTypes.size()) {
    case 0:  paramType = new NameExpr("Nothing"); break;
    case 1:  paramType = paramTypes.get(0); break;
    default: paramType = new TupleExpr(paramTypes);
    }
    
    // Parse the return type, if any.
    Expr returnType = null;
    if (match(TokenType.RIGHT_PAREN)) {
      // No return type, so infer dynamic.
      returnType = new NameExpr("Dynamic");
    } else {
      consume(TokenType.ARROW);
      
      if (lookAhead(TokenType.RIGHT_PAREN)) {
        // An arrow, but no return type, so infer nothing.
        returnType = new NameExpr("Nothing");
      } else {
        returnType = typeDeclaration();
      }
      consume(TokenType.RIGHT_PAREN);
    }
    
    return new FunctionType(paramType, returnType);
  }
  
  private Expr typeDeclaration() {
    // Any Magpie expression can be used as a type declaration. If omitted, it
    // defaults to dynamically typed.
    Expr type = primary();
    if (type != null) return type;
    
    return new NameExpr("Dynamic");
  }
}