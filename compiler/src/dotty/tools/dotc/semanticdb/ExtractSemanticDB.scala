package dotty.tools
package dotc
package semanticdb

import core._
import Phases._
import ast.Trees._
import ast.untpd
import Contexts._
import Symbols._
import Flags._
import Decorators._
import Names.Name
import StdNames.nme
import util.Spans.Span
import util.{SourceFile, SourcePosition}
import collection.mutable
import java.lang.Character.{isJavaIdentifierPart, isJavaIdentifierStart}
import java.nio.file.Paths

/** Extract symbol references and uses to semanticdb files.
 *  See https://scalameta.org/docs/semanticdb/specification.html#symbol-1
 *  for a description of the format.
 *  TODO: Also extract type information
 */
class ExtractSemanticDB extends Phase {
  import ast.tpd._

  override val phaseName: String = ExtractSemanticDB.name

  override def isRunnable(implicit ctx: Context) =
    super.isRunnable && ctx.settings.Ysemanticdb.value

  // Check not needed since it does not transform trees
  override def isCheckable: Boolean = false

  override def run(implicit ctx: Context): Unit =
    val unit = ctx.compilationUnit
    val extract = Extractor()
    extract.traverse(unit.tpdTree)
    ExtractSemanticDB.write(unit.source, extract.occurrences.toList, extract.symbols.toList)

  /** Extractor of symbol occurrences from trees */
  class Extractor extends TreeTraverser {

    private var nextLocalIdx: Int = 0

    /** The index of a local symbol */
    private val locals = mutable.HashMap[Symbol, Int]()

    /** The local symbol(s) starting at given offset */
    private val symsAtOffset = new mutable.HashMap[Int, Set[Symbol]]() {
      override def default(key: Int) = Set[Symbol]()
    }

    /** The extracted symbol occurrences */
    val occurrences = new mutable.ListBuffer[SymbolOccurrence]()

    /** The extracted symbol infos */
    val symbols = new mutable.HashSet[SymbolInformation]()

    /** The symbol occurrences generated so far, as a set */
    private val generated = new mutable.HashSet[SymbolOccurrence]

    private val unicodeEscape = raw"\$$u(\p{XDigit}{4})".r

    /** Add semanticdb name of the given symbol to string builder */
    private def addSymName(b: StringBuilder, sym: Symbol)(given ctx: Context): Unit =
      import Scala._

      def isJavaIdent(str: String) =
        isJavaIdentifierStart(str.head) && str.tail.forall(isJavaIdentifierPart)

      def (name: Name) unescapeUnicode = {
        unicodeEscape.replaceAllIn(name.toString, m =>
          String.valueOf(Integer.parseInt(m.group(1), 16).toChar)
        )
      }

      def addName(name: Name) =
        val str = name.unescapeUnicode
        if isJavaIdent(str) then b `append` str
        else b append '`' append str append '`'

      /** Is symbol global? Non-global symbols get localX names */
      def isGlobal(sym: Symbol): Boolean =
        sym.is(Package)
        || !sym.isSelfSym && (sym.is(Param) || sym.owner.isClass) && isGlobal(sym.owner)

      def addOwner(owner: Symbol): Unit =
        if !owner.isRoot then addSymName(b, owner)

      def addOverloadIdx(sym: Symbol): Unit =
        val decls = {
          val decls0 = sym.owner.info.decls.lookupAll(sym.name)
          if sym.owner.isAllOf(JavaModule)
            decls0 ++ sym.owner.companionClass.info.decls.lookupAll(sym.name)
          else
            decls0
        }
        val alts = decls.filter(_.is(Method)).toList.reverse
        alts match
        case notSym :: rest if sym != notSym =>
          val idx = rest.indexOf(sym).ensuring(_ >= 0)
          b.append('+').append(idx + 1)
        case _ =>

      def (sym: Symbol) isScala2PackageObject(given Context): Boolean =
        sym.name == nme.PACKAGE && sym.owner.is(Package) && sym.is(Module)

      def addDescriptor(sym: Symbol): Unit =
        if sym.is(ModuleClass) then
          addDescriptor(sym.sourceModule)
        else if sym.is(TypeParam) then
          b.append('['); addName(sym.name); b.append(']')
        else if sym.is(Param) then
          b.append('('); addName(sym.name); b.append(')')
        else if sym.isRoot then
          b.append(Symbols.RootPackage)
        else if sym.isEmptyPackage then
          b.append(Symbols.EmptyPackage)
        else if (sym.isScala2PackageObject) then
          b.append(Symbols.PackageObjectDescriptor)
        else
          addName(sym.name)
          if sym.is(Package) then b.append('/')
          else if sym.isType || sym.isAllOf(JavaModule) then b.append('#')
          else if sym.isOneOf(Method | Mutable)
          && (!sym.is(StableRealizable) || sym.name == nme.CONSTRUCTOR) then
            b.append('('); addOverloadIdx(sym); b.append(").")
          else b.append('.')

      /** The index of local symbol `sym`. Symbols with the same name and
       *  the same starting position have the same index.
       */
      def localIdx(sym: Symbol)(given Context): Int =
        def computeLocalIdx(): Int =
          symsAtOffset(sym.span.start).find(_.name == sym.name) match
            case Some(other) => localIdx(other)
            case None =>
              val idx = nextLocalIdx
              nextLocalIdx += 1
              locals(sym) = idx
              symsAtOffset(sym.span.start) += sym
              idx
        locals.getOrElseUpdate(sym, computeLocalIdx())

      if sym.exists then
        if isGlobal(sym) then
          addOwner(sym.owner); addDescriptor(sym)
        else
          b.append(Symbols.LocalPrefix).append(localIdx(sym))

    end addSymName

    /** The semanticdb name of the given symbol */
    private def symbolName(sym: Symbol)(given ctx: Context): String =
      val b = StringBuilder()
      addSymName(b, sym)
      b.toString

    private def source(given ctx: Context) = ctx.compilationUnit.source

    private def range(span: Span)(given ctx: Context): Option[Range] =
      def lineCol(offset: Int) = (source.offsetToLine(offset), source.column(offset))
      val (startLine, startCol) = lineCol(span.start)
      val (endLine, endCol) = lineCol(span.end)
      Some(Range(startLine, startCol, endLine, endCol))

    private val WILDCARDTypeName = nme.WILDCARD.toTypeName

    private def isWildcard(name: Name)(given ctx: Context) = name match
      case nme.WILDCARD | WILDCARDTypeName           => true
      case _ if name.is(NameKinds.WildcardParamName) => true
      case _                                         => false

    /** Definitions of this symbol should be excluded from semanticdb */
    private def excludeDef(sym: Symbol)(given Context): Boolean =
      !sym.exists
      || sym.isLocalDummy
      || sym.is(Synthetic) || (sym.owner.is(Synthetic) && !sym.isAllOf(EnumCase))
      || sym.isAnonymous
      || excludeDefStrict(sym)

    private def excludeDefStrict(sym: Symbol)(given Context): Boolean =
      sym.name.is(NameKinds.DefaultGetterName)

    private def (sym: Symbol) isAnonymous(given Context): Boolean =
      sym.isAnonymousClass
      || sym.isAnonymousModuleVal
      || sym.isAnonymousFunction

    /** Uses of this symbol where the reference has given span should be excluded from semanticdb */
    private def excludeUseStrict(sym: Symbol, span: Span)(given Context): Boolean =
      excludeDefStrict(sym) || (excludeDef(sym) && span.start == span.end)

    private def symbolKind(sym: Symbol)(given Context): SymbolInformation.Kind =
      if sym.name == nme.CONSTRUCTOR
        SymbolInformation.Kind.CONSTRUCTOR
      else
        SymbolInformation.Kind.UNKNOWN_KIND

    private def symbolProps(sym: Symbol)(given Context): Set[SymbolInformation.Property] =
      if sym.isPrimaryConstructor
        Set(SymbolInformation.Property.PRIMARY)
      else
        Set.empty

    private def symbolInfo(sym: Symbol, symbolName: String)(given Context): SymbolInformation = SymbolInformation(
      symbol = symbolName,
      language = Language.SCALA,
      kind = symbolKind(sym),
      properties = symbolProps(sym).foldLeft(0)(_ | _.value),
      displayName = (if sym.is(ModuleClass) then sym.sourceModule else sym).name.show
    )

    private def registerSymbol(sym: Symbol, symbolName: String)(given Context): Unit =
      symbols += symbolInfo(sym, symbolName)

    private def registerOccurrence(symbol: String, span: Span, role: SymbolOccurrence.Role)(given Context): Unit =
      val occ = SymbolOccurrence(symbol, range(span), role)
      if !generated.contains(occ) && occ.symbol.nonEmpty then
        occurrences += occ
        generated += occ

    private def registerUse(sym: Symbol, span: Span)(given Context) =
      if !excludeUseStrict(sym, span) && !isWildcard(sym.name) then
        registerOccurrence(symbolName(sym), span, SymbolOccurrence.Role.REFERENCE)

    private def registerDefinition(sym: Symbol, span: Span)(given Context) =
      if !isWildcard(sym.name) then
        val symbol = symbolName(sym)
        registerOccurrence(symbol, span, SymbolOccurrence.Role.DEFINITION)
        if !sym.is(Package)
          registerSymbol(sym, symbol)

    private def spanOfSymbol(sym: Symbol, span: Span)(given Context): Span = {
      val contents = if source.exists then source.content() else Array.empty[Char]
      val idx = contents.indexOfSlice(sym.name.show, span.start)
      val start = if idx >= 0 then idx else span.start
      Span(start, start + sym.name.show.length, start)
    }

    override def traverse(tree: Tree)(given ctx: Context): Unit =
      for annot <- tree.symbol.annotations do
        if annot.tree.span.exists
          && annot.symbol.owner != defn.ScalaAnnotationInternal
        then
          traverse(annot.tree)

      tree match
        case tree: ValDef if tree.symbol.is(Module) => // skip module val
        case tree: NamedDefTree
        if !excludeDef(tree.symbol) && tree.span.start != tree.span.end =>
          registerDefinition(tree.symbol, tree.nameSpan)
          val privateWithin = tree.symbol.privateWithin
          if privateWithin `ne` NoSymbol
            registerUse(privateWithin, spanOfSymbol(privateWithin, tree.span))
          traverseChildren(tree)
        case tree: (ValDef | DefDef | TypeDef) if tree.symbol.is(Synthetic, butNot=Module) && !tree.symbol.isAnonymous => // skip
        case tree: Template =>
          registerDefinition(tree.constr.symbol, tree.constr.span)
          for
            vparams <- tree.constr.vparamss
            vparam <- vparams
          do
            traverse(vparam.tpt) // the accessor symbol is traversed in the body
          for parent <- tree.parentsOrDerived do
            if
              parent.symbol != defn.ObjectClass.primaryConstructor
              && parent.tpe.dealias != defn.SerializableType
              && parent.symbol != defn.ProductClass
            then
              traverse(parent)
          val selfSpan = tree.self.span
          if selfSpan.exists && selfSpan.start != selfSpan.end then
            traverse(tree.self)
          tree.body.foreach(traverse)
        case tree: Ident =>
          if tree.name != nme.WILDCARD && !excludeUseStrict(tree.symbol, tree.span) then
            registerUse(tree.symbol, tree.span)
        case tree: Select =>
          val qualSpan = tree.qualifier.span
          if !excludeUseStrict(tree.symbol, tree.span) then
            val end = tree.span.end
            val limit = qualSpan.end
            val start =
              if limit < end then
                val len = tree.name.toString.length
                if source.content()(end - 1) == '`' then end - len - 1 else end - len
              else limit
            registerUse(tree.symbol, Span(start max limit, end))
          if qualSpan.exists && qualSpan.start != qualSpan.end then
            traverseChildren(tree)
        case tree: Import =>
          if tree.span.exists && tree.span.start != tree.span.end then
            for sel <- tree.selectors do
              val imported = sel.imported.name
              if imported != nme.WILDCARD then
                for alt <- tree.expr.tpe.member(imported).alternatives do
                  registerUse(alt.symbol, sel.imported.span)
                  if (alt.symbol.companionClass.exists)
                    registerUse(alt.symbol.companionClass, sel.imported.span)
            traverseChildren(tree)
        case tree: Inlined =>
          traverse(tree.call)
        case tree: Annotated => // skip the annotation (see `@param` in https://github.com/scalameta/scalameta/blob/633824474e99bbfefe12ad0cc73da1fe064b3e9b/tests/jvm/src/test/resources/example/Annotations.scala#L37)
          traverse(tree.arg)
        case _ =>
          traverseChildren(tree)
  }
}

object ExtractSemanticDB {
  import java.nio.file.Path
  import scala.collection.JavaConverters._
  import java.nio.file.Files

  val name: String = "extractSemanticDB"

  def write(source: SourceFile, occurrences: List[SymbolOccurrence], symbols: List[SymbolInformation])(given ctx: Context): Unit =
    def absolutePath(path: Path): Path = path.toAbsolutePath.normalize
    val sourcePath = absolutePath(source.file.jpath)
    val sourceRoot = absolutePath(Paths.get(ctx.settings.sourceroot.value))
    val targetRoot =
      val targetRootSetting = ctx.settings.targetroot.value
      absolutePath(
        if targetRootSetting.isEmpty then ctx.settings.outputDir.value.jpath
        else Paths.get(targetRootSetting)
      )
    val relPath = sourceRoot.relativize(sourcePath)
    val relURI = relPath.iterator().asScala.mkString("/")
    val outpath = targetRoot
      .resolve("META-INF")
      .resolve("semanticdb")
      .resolve(relPath)
      .resolveSibling(sourcePath.getFileName().toString() + ".semanticdb")
    Files.createDirectories(outpath.getParent())
    val doc: TextDocument = TextDocument(
      schema = Schema.SEMANTICDB4,
      language = Language.SCALA,
      uri = relURI,
      text = "",
      md5 = internal.MD5.compute(String(source.content)),
      symbols = symbols,
      occurrences = occurrences
    )
    val docs = TextDocuments(List(doc))
    val out = Files.newOutputStream(outpath)
    try
      val stream = internal.SemanticdbOutputStream.newInstance(out)
      docs.writeTo(stream)
      stream.flush()
    finally
      out.close()
}
