package com.soulever.makro

import scala.reflect.macros.Context
import language.experimental.macros

class MacroHelper[C <: Context, FD, Init](val c:C) {

  import c.universe._

  var fdWtt:Option[c.WeakTypeTag[FD]] = None
  var initWtt:Option[c.WeakTypeTag[Init]] = None

  def toDotNotation(s:String) = (s.tail foldLeft (s.head | 32).toChar.toString){(s, c) =>
    s + (if(c.isLower) c.toString else s".$c".toLowerCase)
  }

  def fieldExpansion[A : WeakTypeTag](init:Expr[A])(field:Symbol) = {

    val fieldImplType = fdWtt.get.tpe.member(newTypeName("FieldType")).asType.toType.normalize.typeConstructor

    val mapping = field.annotations.collectFirst {
      case s if s.tpe.typeConstructor.toString == "com.soulever.makro.mapping" =>
        val f = s.scalaArgs.head
        q"$f($init)"
    }

    def expandParameters(s: Type, collector: List[Tree] = List.empty): List[Tree] = {
      val TypeRef(pre, _, args) = s
      args match {
        case Nil if s <:< weakTypeOf[Enumeration#Value] =>
          q"enumFieldProvider[$pre](${pre.termSymbol})" :: collector
        case Nil =>

          q"implicitly[com.soulever.makro.TypeFieldProvider[$s, $fieldImplType]]" :: collector
        case x :: Nil if s.typeConstructor.toString == "com.soulever.makro.types.Mapping" =>
          if (mapping.isEmpty) c.error(implicitly[WeakTypeTag[A]].tpe.typeSymbol.pos, "Cannot find mapping for the given type")
          q"mappingFieldProvider[$x](${mapping.get})" :: collector
        case x :: Nil =>
          expandParameters(x, q"implicitly[com.soulever.makro.KindFieldProvider[${s.typeConstructor}, $fieldImplType]]" :: collector)
        case _ => q"implicitly[com.soulever.makro.TypeFieldProvider[$s, $fieldImplType]]" :: collector
      }
    }

    val innerField = {
      val types = expandParameters(field.typeSignature)
      (types.tail foldLeft q"${types.head}.field(m)"){
        case (quo, tpe) =>
          q"$tpe.field($quo)(m)"
      }
    }

    val fieldName = newTermName(c.fresh() + "Field")

    val name = implicitly[WeakTypeTag[A]].tpe.typeSymbol.name

    val i18nKey = c.literal(toDotNotation(name.toString) + "." + toDotNotation(field.name.toString))

    val validations = {
      val validations = field.annotations.filter(_.tpe <:< weakTypeOf[FieldValidation[_]])
      validations.foreach { v =>
        val fv = weakTypeOf[FieldValidation[_]].typeSymbol.asClass
        val inner = fv.typeParams(0).asType.toType.asSeenFrom(v.tpe, fv)
        val valid_? : Boolean = inner <:< field.typeSignature
        if (!valid_?) c.error(implicitly[WeakTypeTag[A]].tpe.typeSymbol.pos,
          s""" annotated validation ${v.tpe} in field ${implicitly[WeakTypeTag[A]].tpe.typeSymbol.fullName}.${field.name} is incompatible;
              | found    : FieldValidation[${field.typeSignature}]
              | required : FieldValidation[$inner]
              | """.stripMargin)
      }
      validations map {
        a => q""" { (x:${field.typeSignature}) =>
        val validator = ${a.tpe.typeSymbol.companionSymbol}(..${a.scalaArgs})
        Option(x).filter(validator.validate).toRight($i18nKey + s"[$${validator.message}]")
      }"""
      }
    }

    (fieldName, field, List(
      q"val $fieldName = m.field[${field.typeSignature}](${q"$init.${field.name}"}, $i18nKey, $innerField, List(..$validations), i18n = i18n)"))
  }


}
