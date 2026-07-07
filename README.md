# Gerontoapp (sistema de deteccion de caidas en adultos mayores)

Sistema inteligente de detección automática de caídas con sincronización en tiempo real entre dispositivo móvil y reloj inteligente.

## 📋 Descripción del Proyecto

Este proyecto es un sistema de gestión y detección de caídas desarrollado para permitir a usuarios monitorear eventos de caídas en tiempo real, gestionar medicamentos y sincronizar información entre un smartphone y un reloj inteligente. Proporciona funcionalidades para detectar caídas automáticamente, registrar eventos, gestionar medicamentos con recordatorios y sincronizar datos en la nube.

## ✨ Características Principales

- **Detección Automática de Caídas**: Monitoreo continuo mediante acelerómetro y giroscopio
- **Confirmación de Eventos**: El usuario puede confirmar o cancelar caídas detectadas
- **Sincronización en Tiempo Real**: Datos sincronizados entre smartphone y reloj inteligente
- **Gestión de Medicamentos**: Registro de medicinas con recordatorios programados
- **Historial de Eventos**: Todos los eventos guardados en base de datos local y en la nube
- **Funcionamiento Offline**: La aplicación funciona sin conexión a internet
- **Botón SOS**: Alerta manual para emergencias
- **Base de Datos Local**: SQLite para almacenamiento offline
- **Backend en la Nube**: Firebase para sincronización y respaldo

## 🏗️ Arquitectura del Sistema Distribuido

### Arquitectura General

```
┌─────────────────────────────────────────────────────────┐
│                   FIREBASE (Backend)                    │
│              Base de Datos en la Nube                   │
│         - Sincronización de Eventos                     │
│         - Historial Permanente                          │
│         - Respaldo de Datos                             │
└─────────────────────┬───────────────────────────────────┘
                      │
          ┌───────────┴───────────┐
          │                       │
    ┌─────▼──────┐         ┌──────▼─────┐
    │  SMARTPHONE │◄──────►│  RELOJ WEAR │
    │   (Android) │ BT/WiFi │    (Wear OS)│
    │             │         │             │
    │ • Detectar  │         │ • Alerta    │
    │ • Gestionar │         │ • Confirmar │
    │ • Historial │         │ • Medicinas │
    └─────┬──────┘         └──────┬─────┘
          │                       │
          └───────────┬───────────┘
                      │
              ┌───────▼────────┐
              │  Sincronización│
              │  - Bluetooth   │
              │  - WiFi        │
              │  - Internet    │
              └────────────────┘
```

### Capas de la Aplicación

#### Capa 1: Presentación (UI)
- **Smartphone**: Interfaz completa con todas las funcionalidades
- **Reloj**: Interfaz optimizada para pantalla pequeña
- Componentes visuales reactivos con Jetpack Compose

#### Capa 2: Lógica de Negocio
- Detector de caídas (algoritmo de análisis de sensores)
- Gestor de medicamentos (alarmas y recordatorios)
- Validaciones y reglas de negocio
- Gestión de estado de la aplicación

#### Capa 3: Acceso a Datos
- Sincronizador Firebase (cloud)
- Gestor de base de datos local (SQLite)
- Monitor de conectividad
- Comunicación inter-dispositivos

#### Capa 4: Fuentes de Datos
- Firebase Realtime Database
- Room Database (SQLite local)
- Sensores del dispositivo
- APIs de sincronización

## 🎯 Flujos Principales del Sistema

### Flujo 1: Detección de Caída

```
Usuario en Movimiento
        ↓
Lectura de Sensores (Acelerómetro + Giroscopio)
        ↓
¿Cambio Brusco de Velocidad?
        ↓ SÍ
Alerta Visual + Vibración
        ↓
Confirmación del Usuario (3-5 segundos)
        ↓
¿Confirmó?
        ├─ SÍ → Guardar Evento → Enviar a Firebase → Notificar Reloj
        └─ NO → Cancelar Evento → Continuar Monitoreo
```

### Flujo 2: Sincronización de Datos

```
Evento Generado en Smartphone
        ↓
Guardar en Base de Datos Local
        ↓
¿Hay Conexión a Internet?
        ├─ SÍ → Enviar a Firebase → Notificar Reloj → Confirmar
        └─ NO → Marcar como Pendiente → Esperar Conexión
                        ↓
                    (Reconecta)
                        ↓
                    Sincronizar Pendientes
```

### Flujo 3: Gestión de Medicamentos

```
Usuario Agrega Medicamento
        ↓
Crear Alarma para Hora Programada
        ↓
Sincronizar al Reloj
        ↓
(En Hora Programada)
        ↓
Mostrar Notificación en Smartphone y Reloj
        ↓
Usuario Marca como "Tomado"
        ↓
Guardar en Historial + Sincronizar
```

## 📱 Componentes Principales

### 1. Detector de Caídas
Monitorea continuamente los sensores del dispositivo:
- Analiza aceleración y cambios rotacionales
- Aplica algoritmo inteligente de detección
- Genera alertas con confirmación

### 2. Sincronizador Firebase
Gestiona la comunicación con la nube:
- Autentica dispositivos de forma segura
- Sincroniza eventos y datos
- Mantiene historial permanente

### 3. Gestor de Comunicación Wear
Permite comunicación entre dispositivos:
- Envía alertas de caídas al reloj
- Transmite medicamentos
- Sincroniza confirmaciones

### 4. Base de Datos Local (SQLite)
Almacenamiento offline:
- Guarda eventos sin conexión
- Permite consultas rápidas
- Sincroniza automáticamente

### 5. Gestor de Medicamentos
Controla recordatorios:
- Programa alarmas
- Muestra notificaciones
- Registra confirmaciones

### 6. Monitor de Conectividad
Detecta cambios de red:
- Avisa de conexión/desconexión
- Dispara sincronización automática
- Maneja transiciones online/offline

## 🛠️ Tecnologías Utilizadas

### Frontend (UI)
- **Kotlin**: Lenguaje principal
- **Jetpack Compose**: UI moderna y reactiva
- **Compose for Wear OS**: Interfaz para reloj

### Backend
- **Python**: (Opcional, para servicios adicionales)
- **Firebase Realtime Database**: Base de datos en tiempo real
- **Firebase Authentication**: Autenticación segura

### Base de Datos Local
- **Room Database**: ORM para SQLite
- **SQLite**: Motor de base de datos local

### Sensores y Comunicación
- **Acelerómetro**: Detección de movimiento
- **Giroscopio**: Detección de rotación
- **Wearable API**: Comunicación Bluetooth/WiFi
- **Android Notifications**: Sistema de notificaciones

### Herramientas de Desarrollo
- **Android Studio**: IDE principal
- **Gradle**: Build system
- **Kotlin Coroutines**: Programación asíncrona

## 📊 Esquema de Base de Datos

### Tabla: Evento
| Campo | Tipo | Restricciones |
|-------|------|---------------|
| Evento_ID | INT | PRIMARY KEY |
| Tipo | VARCHAR(50) | NOT NULL (CAIDA, SOS, MEDICAMENTO) |
| Timestamp | DATETIME | NOT NULL |
| Confirmado | BOOLEAN | NOT NULL |
| Ubicacion | VARCHAR(255) | |
| Detalles | TEXT | |

### Tabla: Medicamento
| Campo | Tipo | Restricciones |
|-------|------|---------------|
| Medicamento_ID | INT | PRIMARY KEY |
| Nombre | VARCHAR(255) | NOT NULL |
| Dosis | VARCHAR(100) | |
| Horario | TIME | NOT NULL |
| Frecuencia | VARCHAR(50) | |
| Activo | BOOLEAN | NOT NULL |

### Tabla: Historial_Medicamentos
| Campo | Tipo | Restricciones |
|-------|------|---------------|
| Historial_ID | INT | PRIMARY KEY |
| Medicamento_ID | INT | FOREIGN KEY |
| Fecha_Tomado | DATE | |
| Confirmado | BOOLEAN | |
| Sincronizado | BOOLEAN | |

### Tabla: Sincronizacion
| Campo | Tipo | Restricciones |
|-------|------|---------------|
| Sync_ID | INT | PRIMARY KEY |
| Evento_ID | INT | FOREIGN KEY |
| Fecha_Sincronizacion | DATETIME | |
| Estado | VARCHAR(50) | (PENDIENTE, SINCRONIZADO) |
| Dispositivo_Origen | VARCHAR(50) | (MOVIL, RELOJ) |

## 📸 Pantallas de la Aplicación

### En el Smartphone

#### Pantalla 1: Inicio de Sesión
<img src="imagenes/pantallamovil_0.jpeg" width="250" alt="Inicio de Sesión">
Inicio de Sesión. Acceso al sistema y configuración de perfil de usuario.

#### Pantalla 2: Registro
<img src="imagenes/pantallamocil_7.jpeg" width="250" alt="Registro">
Registro. Acceso al sistema y configuración de perfil de usuario.

#### Pantalla 3: Perfil del Paciente
<img src="imagenes/pantallamovil_6.jpeg" width="250" alt="Perfil del Paciente">
Perfil del Paciente. Acceso al sistema y configuración de perfil de usuario.

#### Pantalla 4: Panel Principal
<img src="imagenes/pantallamovil_2.jpeg" width="250" alt="Panel Principal">
Panel Principal. Panel de control y gestión de historial preventivo.

#### Pantalla 5: Configuración de Alertas
<img src="imagenes/pantallamovil_3.jpeg" width="250" alt="Configuración de Alertas">
Configuración de Alertas. Panel de control y gestión de historial preventivo.

#### Pantalla 6: Historial Médico
<img src="imagenes/pantallamovil_4.jpeg" width="250" alt="Historial Médico">
Historial Médico. Panel de control y gestión de historial preventivo.

#### Pantalla 7: Lista de Contactos
<img src="imagenes/pantallamovil_5.jpeg" width="250" alt="Lista de Contactos">
Lista de Contactos. Gestión de red de apoyo, registro de nuevos usuarios y sincronización del wearable.

#### Pantalla 8: Registro de Usuario
<img src="imagenes/pantallamovil_1.jpeg" width="250" alt="Registro de Usuario">
Registro de Usuario. Gestión de red de apoyo, registro de nuevos usuarios y sincronización del wearable.

#### Pantalla 9: Sincronización de Reloj
<img src="imagenes/pantallamovil_8.jpeg" width="250" alt="Sincronización de Reloj">
Sincronización de Reloj. Gestión de red de apoyo, registro de nuevos usuarios y sincronización del wearable.

### En el Reloj Inteligente

#### Pantalla 10: Reloj - Pantalla de Sincronización
<img src="imagenes/reloj_pantalla0.png" width="220" alt="Pantalla de Sincronización Wear OS">
Pantalla de sincronización. Interfaz de usuario optimizada para Smartwatch con Wear OS.

#### Pantalla 11: Reloj - Pantalla Inicio, Alerta de Ayuda
<img src="imagenes/reloj_pantalla1.png" width="220" alt="Pantalla Inicio Alerta de Ayuda">
Pantalla Inicio, Alerta de ayuda. Interfaz de usuario optimizada para Smartwatch con Wear OS.

#### Pantalla 12: Reloj - Medicamentos
<img src="imagenes/reloj_pantalla2.png" width="220" alt="Medicamentos Wear OS">
Medicamentos. Interfaz de usuario optimizada para Smartwatch con Wear OS.

#### Pantalla 13: Reloj - Historial de Caída
<img src="imagenes/reloj_pantalla3.png" width="220" alt="Historial de Caída">
Historial de caída. Registro de eventos y alertas generadas.

## 🚀 Instalación y Uso

### Requisitos Previos
- Android 8.0 o superior (Smartphone)
- Wear OS 6.0 o superior (Reloj)
- Conexión a internet (para sincronización)
- Cuenta de Google (para Firebase)

### Pasos de Instalación

1. **Clonar el Repositorio**
```bash
git clone https://github.com/Freddyrex/Gerontoapp_app_deteccion_de_caidas_en_adultos_mayores_-app_movil_y_-wearable-/tree/main
```

2. **Configurar Firebase**
- Crear proyecto en Firebase Console
- Descargar `google-services.json`
- Colocar en carpeta `app/`

3. **Instalar en Smartphone**
- Compilar: `./gradlew build`
- Instalar: `./gradlew installDebug`

4. **Instalar en Reloj**
- Conectar reloj por USB o WiFi
- Ejecutar: `./gradlew installDebug`

5. **Configurar Base de Datos**
- Verificar puertos de comunicación
- Instalar dependencias necesarias
- Habilitar sincronización automática

## 🔑 Funcionalidades Principales

### En el Smartphone
✅ Detectar caídas automáticamente  
✅ Confirmar o cancelar eventos  
✅ Ver historial completo  
✅ Gestionar medicamentos  
✅ Recibir recordatorios  
✅ Botón SOS manual  
✅ Configurar sensibilidad  
✅ Ver estado de sincronización  

### En el Reloj
✅ Alerta inmediata de caída  
✅ Confirmar caída con botones grandes  
✅ Ver próximos medicamentos  
✅ Recibir notificaciones  
✅ Botón SOS accesible  
✅ Indicador de batería  
✅ Estado de conectividad  
✅ Complicación en esfera  

## 🔄 Ciclo de Sincronización

### Detección de Conectividad
- Sistema detecta cambios de red
- Al conectarse → inicia sincronización
- Si desconecta → guarda localmente

### Sincronización Bidireccional
- Envía datos nuevos a Firebase
- Recibe cambios de otros dispositivos
- Actualiza interfaz automáticamente

### Entre Dispositivos
- Smartphone notifica al reloj
- Reloj confirma recepción
- Ambos muestran información actual

## 📡 Configuración de Puertos

Para permitir comunicación correcta entre componentes:

- **API REST**: Puerto 5000 (configurable)
- **Firebase**: Automático (HTTPS)
- **Base de Datos Local**: Integrada (SQLite)
- **Bluetooth**: Automático (Wear OS)

## 🔒 Seguridad

- Autenticación con Firebase Anonymous Auth
- Datos encriptados en tránsito (HTTPS/TLS)
- Almacenamiento seguro en SQLite
- Validación de todos los datos
- Control de privacidad del usuario

## 📝 Permisos Requeridos

- Acceso a sensores (acelerómetro, giroscopio)
- Conexión a internet
- Permiso para notificaciones
- Permiso para crear alarmas
- Permiso de almacenamiento local
- Permiso Bluetooth (para Wear)

## 🎯 Próximas Mejoras

- [ ] Integración con contactos de emergencia
- [ ] Aprendizaje automático para reducir falsas alarmas
- [ ] Registro médico personalizado
- [ ] Exportación de reportes
- [ ] Interfaz web de administración
- [ ] Soporte multi-usuario
- [ ] Integración con wearables adicionales

## 📞 Soporte y Contacto

**Desarrollador Principal**: Freddy Valenzuela  
**Versión**: 1.0  
**Última Actualización**: Abril 2026  

## 📄 Licencia

Este proyecto está disponible bajo licencia MIT.

---

## Créditos

Este proyecto fue desarrollado por **Freddy Valenzuela** como solución integral de detección de caídas utilizando tecnología móvil y wearable.

```
                    ▐▀▄───────▄▀▌───▄▄▄▄▄▄▄─────────
                    ▌▒▒▀▄▄▄▄▄▀▒▒▐▄▀▀▒██▒██▒▀▀▄──────
                   ▐▒▒▒▒▀▒▀▒▀▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▀▄────
                   ▌▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▄▒▒▒▒▒▒▒▒▒▒▒▒▀▄──
                  ▀█▒▒▒█▌▒▒█▒▒▐█▒▒▒▀▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▌
                  ▀▌▒▒▒▒▒▒▀▒▀▒▒▒▒▒▒▀▀▒▒▒▒▒▒▒▒▒▒▒▒▒▒▐
                  ▐▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▌
                  ▐▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒█
                  ▐▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒█
                  ▐▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▌
                  ─▌▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▐
                  ─▐▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▌
                  ──▌▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▐
                  ──▐▄▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▒▄▌
                  ────▀▄▄▀▀▀▀▀▄▄▀▀▀▀▀▀▀▄▄▀▀▀▀▀▄▄▀────
```

---

**Hecho con ❤️ por Freddy Valenzuela**

