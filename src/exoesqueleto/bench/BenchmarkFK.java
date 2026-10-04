package exoesqueleto.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import exoesqueleto.kinematics.ForwardKinematics3D;
import exoesqueleto.kinematics.Matrix4;
import exoesqueleto.kinematics.Segment;

/**
 * Experimento para responder a la pregunta: ¿y si el árbol cinemático tuviera
 * decenas de miles de nodos?
 *
 * Se prueban dos formas de árbol extremas con el mismo número de nodos n:
 * <ol>
 * <li>Árbol ANCHO: cada segmento nuevo cuelga de uno ya existente elegido al
 * azar. Sale un árbol "frondoso" con profundidad pequeña (del orden de log n).
 * La recursión funciona sin problema.</li>
 * <li>CADENA profunda: cada segmento es hijo del anterior (profundidad = n). La
 * versión recursiva acaba en StackOverflowError porque necesita n llamadas
 * anidadas a la vez, y hace falta la iterativa.</li>
 * </ol>
 *
 * Lo que se espera ver: el tiempo crece de forma lineal con n (O(n)) en ambas
 * versiones, pero solo la iterativa sobrevive a la cadena profunda.
 *
 * Nota sobre la medición: aquí no se imprime nada por segmento. En el lab2,
 * el System.out.println de cada llamada tarda mucho más que el cálculo (la
 * consola es lentísima comparada con unas multiplicaciones), así que ese
 * tiempo mide sobre todo la consola, no el algoritmo.
 */
public class BenchmarkFK {

	public static void main(String[] args) {
		System.out.println("Árbol ancho (ramas aleatorias)");
		for (int n : new int[] { 1_000, 10_000, 50_000, 200_000 }) // "_" separa miles, solo estético
			measure(randomTree(n, new Random(42)), n);

		System.out.println("\nCadena profunda (cada segmento hijo del anterior)");
		for (int n : new int[] { 1_000, 10_000, 50_000, 200_000 })
			measure(chain(n), n);
	}

	/** Mide las dos versiones sobre el mismo árbol e imprime una fila de la tabla. */
	private static void measure(Segment root, int n) {
		String rec, it;
		try {
			rec = String.format("%8.2f ms", time(() -> ForwardKinematics3D.computePositions(root, 0, 0, 0)));
		} catch (StackOverflowError e) {
			// StackOverflowError es un Error, no una Exception: normalmente no se
			// captura porque indica un fallo grave. Aquí se captura a propósito,
			// para demostrar que ocurre.
			rec = "StackOverflowError";
		}
		it = String.format("%8.2f ms", time(() -> ForwardKinematics3D.computePositionsIterative(root, 0, 0, 0)));
		System.out.printf("  n = %,8d   recursiva: %-18s iterativa: %s%n", n, rec, it);
	}

	/**
	 * Tiempo típico de una ejecución, en ms: mediana de 7 ejecuciones.
	 *
	 * - Calentamiento: Java empieza interpretando el código y, cuando ve que un
	 *   método se usa mucho, lo compila a código máquina (compilador JIT, "Just
	 *   In Time"). Las primeras ejecuciones son más lentas, así que se descartan
	 *   3.
	 * - Mediana en vez de media: si una ejecución sale muy lenta por algo
	 *   externo (el recolector de basura, otro programa...), la media se
	 *   dispara, pero la mediana no.
	 *
	 * Runnable es una interfaz con un solo método run(): permite pasar "un
	 * trozo de código" como parámetro (con una lambda).
	 */
	private static double time(Runnable r) {
		for (int i = 0; i < 3; i++)
			r.run();
		double[] t = new double[7];
		for (int i = 0; i < t.length; i++) {
			long s = System.nanoTime();
			r.run();
			t[i] = (System.nanoTime() - s) / 1e6;
		}
		Arrays.sort(t);
		return t[t.length / 2]; // el del medio tras ordenar = mediana
	}

	/**
	 * Árbol ancho aleatorio. Con una semilla fija (new Random(42)) los números
	 * "aleatorios" son siempre los mismos, así que el experimento es
	 * reproducible: cada ejecución construye exactamente el mismo árbol.
	 */
	private static Segment randomTree(int n, Random rnd) {
		List<Segment> all = new ArrayList<>();
		Segment root = randomSegment(rnd);
		all.add(root);
		for (int i = 1; i < n; i++) {
			Segment s = randomSegment(rnd);
			all.get(rnd.nextInt(all.size())).addChild(s); // padre al azar
			all.add(s);
		}
		return root;
	}

	/** Cadena: cada segmento nuevo es hijo del último (un "brazo robótico" de n eslabones). */
	private static Segment chain(int n) {
		Random rnd = new Random(7);
		Segment root = randomSegment(rnd), last = root;
		for (int i = 1; i < n; i++) {
			Segment s = randomSegment(rnd);
			last.addChild(s);
			last = s;
		}
		return root;
	}

	/** Segmento de 1 a 6 cm con una rotación base y unos ángulos aleatorios. */
	private static Segment randomSegment(Random rnd) {
		Segment s = new Segment("s", 1 + rnd.nextDouble() * 5, Matrix4.rotY(rnd.nextDouble() - 0.5))
				.joint(0, "x", -45, 45).joint(2, "z", -45, 45);
		s.setAngle(0, rnd.nextDouble() - 0.5);
		s.setAngle(2, rnd.nextDouble() - 0.5);
		return s;
	}
}
