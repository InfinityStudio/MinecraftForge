package cpw.mods.fml.relauncher;

import java.applet.Applet;
import java.applet.AppletStub;
import java.awt.Dialog.ModalityType;
import java.awt.HeadlessException;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.Arrays;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.ProgressMonitorInputStream;

import net.minecraft.src.WorldSettings;

public class FMLEmbeddingRelauncher
{
    private static FMLEmbeddingRelauncher INSTANCE;
    private RelaunchClassLoader clientLoader;
    private Object newApplet;
    private Class<? super Object> appletClass;

    private JDialog popupWindow;

    public static void relaunch(ArgsWrapper wrap)
    {
        instance().relaunchClient(wrap);
    }

    static FMLEmbeddingRelauncher instance()
    {
        if (INSTANCE == null)
        {
            INSTANCE = new FMLEmbeddingRelauncher();
        }
        return INSTANCE;

    }

    private FMLEmbeddingRelauncher()
    {
        URLClassLoader ucl = (URLClassLoader)getClass().getClassLoader();

        clientLoader = new RelaunchClassLoader(ucl.getURLs());

        try
        {
            popupWindow = new JDialog(null, "FML Initial Setup", ModalityType.MODELESS);
            JOptionPane optPane = new JOptionPane("<html><font size=\"+1\">FML Setup</font><br/><br/>FML is performing some configuration, please wait</html>", JOptionPane.INFORMATION_MESSAGE, JOptionPane.DEFAULT_OPTION, null, new Object[0]);
            popupWindow.add(optPane);
            popupWindow.pack();
            popupWindow.setLocationRelativeTo(null);
            popupWindow.setVisible(true);
        }
        catch (Exception e)
        {
            popupWindow = null;
        }
    }

    private void relaunchClient(ArgsWrapper wrap)
    {
        // Now we re-inject the home into the "new" minecraft under our control
        Class<? super Object> client;
        try
        {
            File minecraftHome = setupHome();

            client = ReflectionHelper.getClass(clientLoader, "net.minecraft.client.Minecraft");
            ReflectionHelper.setPrivateValue(client, null, minecraftHome, "field_6275_Z", "ap", "minecraftDir");
        }
        finally
        {
            if (popupWindow!=null)
            {
                popupWindow.setVisible(false);
                popupWindow.dispose();
            }
        }

        try
        {
            ReflectionHelper.findMethod(client, null, new String[] { "fmlReentry" }, ArgsWrapper.class).invoke(null, wrap);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            // Hmmm
        }
    }

    private File setupHome()
    {
        Class<? super Object> mcMaster = ReflectionHelper.getClass(getClass().getClassLoader(), "net.minecraft.client.Minecraft");
        // We force minecraft to setup it's homedir very early on so we can inject stuff into it
        Method setupHome = ReflectionHelper.findMethod(mcMaster, null, new String[] { "func_6240_b", "getMinecraftDir", "b"} );
        try
        {
            setupHome.invoke(null);
        }
        catch (Exception e)
        {
            // Hmmm
        }
        File minecraftHome = ReflectionHelper.getPrivateValue(mcMaster, null, "field_6275_Z", "ap", "minecraftDir");
        FMLLog.minecraftHome = minecraftHome;
        FMLLog.info("FML relaunch active");

        try
        {
            RelaunchLibraryManager.handleLaunch(minecraftHome, clientLoader);
        }
        catch (Throwable t)
        {
            try
            {
                String logFile = new File(minecraftHome,"ForgeModLoader-0.log").getCanonicalPath();
                JOptionPane.showMessageDialog(popupWindow,
                        String.format("<html><div align=\"center\"><font size=\"+1\">There was a fatal error starting up minecraft and FML</font></div><br/>" +
                        		"Minecraft cannot launch in it's current configuration<br/>" +
                        		"Please consult the file <i><a href=\"file:///%s\">%s</a></i> for further information</html>", logFile, logFile
                        		), "Fatal FML error", JOptionPane.ERROR_MESSAGE);
            }
            catch (Exception ex)
            {
                // ah well, we tried
            }
            throw new RuntimeException(t);
        }

        return minecraftHome;
    }

    public static void appletEntry(Applet minecraftApplet)
    {
        instance().relaunchApplet(minecraftApplet);
    }

    private void relaunchApplet(Applet minecraftApplet)
    {
        appletClass = ReflectionHelper.getClass(clientLoader, "net.minecraft.client.MinecraftApplet");
        if (minecraftApplet.getClass().getClassLoader() == clientLoader)
        {
            try
            {
                newApplet = minecraftApplet;
                ReflectionHelper.findMethod(appletClass, newApplet, new String[] {"fmlInitReentry"}).invoke(newApplet);
                return;
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }

        setupHome();

        Class<? super Object> parentAppletClass = ReflectionHelper.getClass(getClass().getClassLoader(), "java.applet.Applet");

        try
        {
            newApplet = appletClass.newInstance();
            Object appletContainer = ReflectionHelper.getPrivateValue(ReflectionHelper.getClass(getClass().getClassLoader(), "java.awt.Component"), minecraftApplet, "parent");

            Class<? super Object> launcherClass = ReflectionHelper.getClass(getClass().getClassLoader(), "net.minecraft.Launcher");
            if (launcherClass.isInstance(appletContainer))
            {
                ReflectionHelper.findMethod(ReflectionHelper.getClass(getClass().getClassLoader(), "java.awt.Container"), minecraftApplet, new String[] { "removeAll" }).invoke(appletContainer);
                ReflectionHelper.findMethod(launcherClass, appletContainer, new String[] { "replace" }, parentAppletClass).invoke(appletContainer, newApplet);
            }
            else
            {
                FMLLog.severe("Found unknown applet parent %s, unable to inject!\n", launcherClass);
                throw new RuntimeException();
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
        finally
        {
            if (popupWindow!=null)
            {
                popupWindow.setVisible(false);
                popupWindow.dispose();
            }
        }
    }

    public static void appletStart(Applet applet)
    {
        instance().startApplet(applet);
    }

    private void startApplet(Applet applet)
    {
        if (applet.getClass().getClassLoader() == clientLoader)
        {
            try
            {
                ReflectionHelper.findMethod(appletClass, newApplet, new String[] {"fmlStartReentry"}).invoke(newApplet);
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
        return;
    }

    public InputStream wrapStream(InputStream inputStream, String infoString)
    {
        if (popupWindow!=null)
        {
            return new ProgressMonitorInputStream(popupWindow, infoString, inputStream);
        }
        else
        {
            return inputStream;
        }
    }
}
