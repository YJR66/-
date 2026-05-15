package com.virtualcamera.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.virtualcamera.R
import com.virtualcamera.databinding.FragmentProviderSelectorBinding
import com.virtualcamera.providers.CanvasDrawProvider
import com.virtualcamera.providers.DemoCanvasProvider
import com.virtualcamera.providers.StaticImageProvider
import com.virtualcamera.virtualcamera.CameraFacing
import com.virtualcamera.virtualcamera.FrameProvider
import com.virtualcamera.virtualcamera.VirtualCameraManager

/**
 * Bottom-sheet style dialog that lets the user select a [FrameProvider] at
 * runtime.
 *
 * After a provider is selected the fragment calls [PreviewActivity.restartCamera]
 * so the change takes effect immediately.
 */
class ProviderSelectorFragment : DialogFragment() {

    companion object {
        const val TAG = "ProviderSelectorFragment"
        private const val ARG_FACING = "facing"

        fun newInstance(facing: CameraFacing) = ProviderSelectorFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_FACING, facing.name)
            }
        }
    }

    private var _binding: FragmentProviderSelectorBinding? = null
    private val binding get() = _binding!!

    private lateinit var facing: CameraFacing

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        facing = CameraFacing.valueOf(requireArguments().getString(ARG_FACING)!!)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProviderSelectorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnProviderCanvas.setOnClickListener {
            applyProvider(DemoCanvasProvider())
        }

        binding.btnProviderStatic.setOnClickListener {
            applyProvider(StaticImageProvider())
        }

        binding.btnCancel.setOnClickListener { dismiss() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun applyProvider(provider: FrameProvider) {
        val manager = VirtualCameraManager.getInstance(requireContext())
        when (facing) {
            CameraFacing.FRONT -> manager.setFrontCameraProvider(provider)
            CameraFacing.BACK -> manager.setBackCameraProvider(provider)
        }
        Toast.makeText(
            requireContext(),
            getString(R.string.provider_applied),
            Toast.LENGTH_SHORT
        ).show()
        (activity as? PreviewActivity)?.restartCamera()
        dismiss()
    }
}
