/**
 * Descriptor del módulo (sistema de módulos de Java, desde Java 9).
 *
 * Desde Java 11, JavaFX ya no viene con el JDK y se distribuye como módulos
 * aparte (los .jar de lib/). Este fichero le dice a Java qué módulos necesita
 * el proyecto ("requires") y qué paquetes propios se dejan usar desde fuera
 * ("exports").
 */
module exoesqueleto {
	// javafx.controls incluye a su vez javafx.graphics (3D, escena) y javafx.base
	requires javafx.controls;
	// Solo para guardar las capturas en PNG (BufferedImage e ImageIO son de AWT)
	requires java.desktop;

	// JavaFX crea la ventana instanciando ExoskeletonApp por reflexión, así que
	// el paquete gui tiene que ser accesible para él
	exports exoesqueleto.gui;
	exports exoesqueleto.kinematics;
	exports exoesqueleto.armor;
	exports exoesqueleto.bench;
}
